package com.keyguard.app.family

import android.app.job.JobParameters
import android.app.job.JobService
import com.keyguard.app.R
import java.util.concurrent.Executors

/**
 * The periodic wakeup that keeps a supervised device honest.
 *
 * Without it, a policy change would only land when the child happened to open the app and
 * events would only leave when they happened to open it again — which is to say, on a device
 * whose user has no reason to open the app, never.
 *
 * A blank endpoint finishes immediately without rescheduling, so a default build with no
 * backend configured does exactly what it does today: nothing on the network.
 */
class SupervisionJobService : JobService() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onStartJob(params: JobParameters?): Boolean {
        val endpoint = getString(R.string.verify_base_url)
        if (endpoint.isBlank() || !Supervision(this).isSupervised) {
            SupervisionSync.cancel(this)
            return false
        }

        executor.execute {
            val sync = SupervisionSync(this, endpoint)
            val reached = runCatching { sync.syncBlocking() }.getOrDefault(false)
            sync.shutdown()
            // Reschedule on failure: a sync that could not reach the server is exactly the one
            // worth retrying, and the periodic job would otherwise wait out its full interval.
            jobFinished(params, !reached)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        executor.shutdownNow()
        return true
    }
}
