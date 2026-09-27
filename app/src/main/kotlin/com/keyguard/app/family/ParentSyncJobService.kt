package com.keyguard.app.family

import android.app.job.JobParameters
import android.app.job.JobService
import com.keyguard.app.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * The periodic wakeup that makes the parent side something other than a screen you remember to
 * check.
 *
 * The mirror of [SupervisionJobService], and it needs to exist for the mirror-image reason:
 * without it, a reported warning waits for the parent to open the app. The child's job runs on
 * a device whose user has no reason to open Keyguard; the parent's runs on a device whose user
 * has every reason to and still should not have to.
 *
 * A blank endpoint or no parent session cancels the job rather than failing it. Both are
 * terminal states for this device — a default build has no server to poll, and a signed-out
 * parent has nothing to poll for — and a periodic job that wakes up every fifteen minutes to
 * discover it has no work is a battery cost with no upside.
 */
class ParentSyncJobService : JobService() {

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * The running sync for each job id. The system reuses one service instance across jobs,
     * so stopping a job cancels its own work and leaves the executor alive. Shutting the
     * executor down there made the next job on the same instance throw on `execute` and
     * crash the app.
     */
    private val running = ConcurrentHashMap<Int, Future<*>>()

    override fun onStartJob(params: JobParameters?): Boolean {
        val endpoint = getString(R.string.verify_base_url)
        if (endpoint.isBlank()) {
            ParentSync.cancel(this)
            return false
        }

        val jobId = params?.jobId ?: 0
        running[jobId] = executor.submit {
            val sync = ParentSync(this, endpoint)
            val reached = runCatching { sync.syncBlocking() }.getOrDefault(false)
            sync.shutdown()
            running.remove(jobId)
            // Reschedule on failure, matching the child job: a poll that could not reach the
            // server is exactly the one worth retrying rather than waiting out the interval.
            jobFinished(params, !reached)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        params?.let { running.remove(it.jobId)?.cancel(true) }
        return true
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
