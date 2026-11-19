package io.github.chris2crafter.thorstore

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** Periodic background check; notifies once per new release of an installed app. */
class UpdateJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            runCatching {
                val updates = Store.checkUpdates()
                val fresh = updates.filter { Store.markNotified(it) }
                if (fresh.isNotEmpty()) Notifications.updates(this, fresh)
            }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        const val JOB_ID = 4711

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            val hours = Store.intervalHours
            if (hours <= 0) {
                js.cancel(JOB_ID)
                return
            }
            val period = hours * 3_600_000L
            val existing = js.getPendingJob(JOB_ID)
            if (existing != null && existing.intervalMillis == period) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(period, 30 * 60_000L)
                    .setPersisted(true)
                    .build(),
            )
        }
    }
}

class StoreApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Store.init(this)
        Notifications.createChannels(this)
        UpdateJobService.schedule(this)
    }
}
