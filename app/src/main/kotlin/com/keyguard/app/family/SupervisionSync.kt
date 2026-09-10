package com.keyguard.app.family

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Moves the queue to the server and the policy back.
 *
 * **Deliberately not called from the IME.** The keyboard writes to [EventQueue] and does
 * nothing else; every network call in this file happens from the app process, driven by an
 * activity or by [SupervisionJobService]. On Android today that is one process and the split
 * buys nothing technical — it is a shape the iOS port cannot do without, since guideline 4.4.1
 * limits a keyboard extension to collecting activity that enhances the keyboard itself, and
 * reporting to a parent plainly is not that. Building it the other way now would mean
 * discovering at port time that the sync lives somewhere it can never live.
 *
 * The order within a sync matters. Policy first, because the answer might be "this device is
 * no longer supervised", and uploading a queue to a family that has removed you wastes a
 * request and stores events a parent has already said they do not want.
 */
class SupervisionSync(private val context: Context, baseUrl: String) {

    private val supervision = Supervision(context)
    private val queue = EventQueue(context)
    private val samples = SampleQueue(context)
    private val client = FamilyClient(context, baseUrl)
    private val executor = Executors.newSingleThreadExecutor()

    val installId: String get() = client.installId

    /** Runs a sync off the main thread. [onDone] receives whether anything reached the server. */
    fun sync(onDone: (Boolean) -> Unit = {}) {
        executor.execute {
            val result = runCatching { syncBlocking() }
                .onFailure { Log.d(TAG, "sync failed: ${it.javaClass.simpleName}") }
                .getOrDefault(false)
            onDone(result)
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    /** The whole exchange, blocking. Safe to call from a job's worker thread. */
    fun syncBlocking(): Boolean {
        if (!supervision.isSupervised) return false

        when (val outcome = client.fetchPolicy()) {
            is PolicyOutcome.Failed -> return false

            is PolicyOutcome.Unsupervised -> {
                // A parent has removed this device. Drop the policy, drop both queues — those
                // records now have no reader — and take the notice down.
                supervision.clearSupervision()
                queue.clear()
                samples.clear()
                SupervisionNotice.hide(context)
                return true
            }

            is PolicyOutcome.Supervised -> {
                // Read before the write, so the comparison is against what this device has
                // actually been enforcing rather than against what just arrived.
                val previous = supervision.policy?.reviewScope
                supervision.updatePolicy(outcome.policy)
                discardSamplesIfScopeNarrowed(previous, outcome.policy.reviewScope)
                // Re-posted with the new scope rather than left alone, so a parent switching to
                // full review changes what the standing notice says on this sync — not at the
                // child's next app launch, which they may have no reason to perform.
                SupervisionNotice.show(context, outcome.policy.reviewScope)
            }
        }

        drainQueue()
        drainSamples()
        supervision.lastSyncAt = System.currentTimeMillis()
        return true
    }

    /**
     * Uploads in batches until the queue empties or the server stops taking them.
     *
     * Bounded by [MAX_BATCHES] so one sync cannot turn into an unbounded upload loop on a
     * device that has been offline for a month; the rest goes on the next run.
     */
    private fun drainQueue() {
        repeat(MAX_BATCHES) {
            val batch = queue.peek()
            if (batch.isEmpty()) return

            when (val outcome = client.uploadEvents(batch)) {
                is UploadOutcome.Failed -> return
                is UploadOutcome.Unsupervised -> {
                    supervision.clearSupervision()
                    queue.clear()
                    SupervisionNotice.hide(context)
                    return
                }
                // Confirmed against what the server *received*, not what it stored: a
                // duplicate it deliberately dropped is still delivered, and leaving it queued
                // would resend it forever.
                is UploadOutcome.Accepted -> queue.confirm(outcome.received)
            }
        }
    }

    /**
     * Uploads activity samples, on the same batching rules as the event queue.
     *
     * Runs after [drainQueue] rather than before it. If a device has been offline long enough
     * for both to back up, the warnings are the half a parent needs first, and a sync that is
     * cut short by a dying connection should have spent its requests on those.
     */
    private fun drainSamples() {
        repeat(MAX_BATCHES) {
            val batch = samples.peek()
            if (batch.isEmpty()) return

            when (val outcome = client.uploadSamples(batch)) {
                is SampleOutcome.Failed -> return
                is SampleOutcome.Unsupervised -> {
                    supervision.clearSupervision()
                    queue.clear()
                    samples.clear()
                    SupervisionNotice.hide(context)
                    return
                }
                is SampleOutcome.ScopeWithdrawn -> {
                    // The parent narrowed the scope while this device was offline. These
                    // samples were collected under a setting that no longer applies, so they
                    // are dropped rather than retried — a parent who turned collection off
                    // must not receive a backlog of it afterwards.
                    samples.clear()
                    return
                }
                is SampleOutcome.Accepted -> samples.confirm(outcome.received)
            }
        }
    }

    /**
     * Throws away queued samples when a policy change means they may no longer be sent.
     *
     * The server refusing them is the backstop; this is the device declining to try. It matters
     * because the two narrowings are different: dropping from FULL_TEXT to THEMES leaves queued
     * samples that still carry message text, and holding those on the device in the hope of a
     * future upload is exactly the state a family just asked to leave.
     *
     * Only narrowing clears. Widening the scope leaves the queue alone — those samples were
     * collected under a *stricter* setting and are still within what the parent now permits.
     */
    private fun discardSamplesIfScopeNarrowed(previous: ReviewScope?, next: ReviewScope) {
        val before = previous ?: return
        if (next.narrowerThan(before)) samples.clear()
    }

    companion object {
        private const val TAG = "KeyguardSupervision"
        private const val MAX_BATCHES = 8

        const val JOB_ID = 4001

        /**
         * Fifteen minutes is the platform floor for a periodic job, and it is the right order
         * of magnitude anyway: a parent dashboard is a thing you check, not a live feed, and
         * promising anything faster would be promising a background wakeup Android will not
         * reliably grant.
         */
        private val INTERVAL_MS = TimeUnit.MINUTES.toMillis(15)

        /** Idempotent — rescheduling an identical job replaces it rather than stacking. */
        fun schedule(context: Context) {
            val job = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, SupervisionJobService::class.java),
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
