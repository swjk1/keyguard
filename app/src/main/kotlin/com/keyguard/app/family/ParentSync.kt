package com.keyguard.app.family

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The parent half of the exchange: fetch what the family has reported, and interrupt if it
 * matters.
 *
 * [SupervisionSync] moves a child's queue up to the server. Nothing moved it the rest of the
 * way. The parent dashboard fetched on `onCreate` and on a Refresh button, which means the
 * product's central promise — *we will tell you if something serious happens* — was only ever
 * kept by a parent who thought to go and look. This closes that leg.
 *
 * ### Why a poll and not a push
 *
 * A push would need Firebase, a device-token registry on the server, a data-safety entry and a
 * dependency the app does not otherwise have; a poll needs a `JobService` the app already has
 * a working example of. The cost is latency, and the latency was already there: the child's
 * upload is on the same fifteen-minute periodic floor, so a push on this leg would have
 * shortened the second half of a delay dominated by the first. Shortening it properly means
 * expediting *both* legs — [SupervisionSync.syncNow] does the child's — and that is worth
 * doing before it is worth adding Firebase.
 *
 * ### Ordering
 *
 * The watermark is written after the notification is attempted, not before, so a process death
 * between the two costs a repeat rather than a miss. [ParentAlertNotice] swallows its own
 * failures, so "attempted" is as strong a guarantee as this side can offer — and the dashboard
 * is the backstop for everything the notification did not manage to say.
 */
class ParentSync(private val context: Context, baseUrl: String) {

    private val client = FamilyClient(context, baseUrl)
    private val supervision = Supervision(context)
    private val executor = Executors.newSingleThreadExecutor()

    /** Runs a poll off the main thread. [onDone] receives whether the server answered. */
    fun sync(onDone: (Boolean) -> Unit = {}) {
        executor.execute {
            val result = runCatching { syncBlocking() }
                .onFailure { Log.d(TAG, "parent sync failed: ${it.javaClass.simpleName}") }
                .getOrDefault(false)
            onDone(result)
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    /** The whole poll, blocking. Safe to call from a job's worker thread. */
    fun syncBlocking(): Boolean {
        // No session means nothing to poll for. Not an error: a parent signs out and the job
        // is cancelled, but a cancellation that races a run must not look like a failure and
        // get itself rescheduled.
        if (!client.hasParentSession) return false

        val overview = client.fetchOverview() ?: return false
        overview.familyId?.let(supervision::becomeParent)

        val digest = ParentAlerts.digest(overview.children, supervision.parentAlertWatermark)
        ParentAlertNotice.show(context, digest)
        supervision.parentAlertWatermark = digest.watermark
        return true
    }

    companion object {
        private const val TAG = "KeyguardParentSync"

        const val JOB_ID = 4002

        /**
         * The same fifteen minutes [SupervisionSync] uses, and for the same reason: it is the
         * platform floor for a periodic job. Asking for less does not produce less; it produces
         * fifteen minutes and a promise the app cannot keep.
         */
        private val INTERVAL_MS = TimeUnit.MINUTES.toMillis(15)

        /** Idempotent — rescheduling an identical job replaces it rather than stacking. */
        fun schedule(context: Context) {
            val job = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, ParentSyncJobService::class.java),
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(INTERVAL_MS)
                .build()

            context.getSystemService(JobScheduler::class.java)?.schedule(job)
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }
    }
}
