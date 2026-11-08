package com.jono.githubwidget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** Refreshes the repo list in the background: every 15 minutes, and right away when asked. */
class RefreshJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val ctx = applicationContext
        Thread {
            try {
                Refresher.refresh(ctx)
            } finally {
                WidgetRenderer.renderAll(ctx)
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val PERIODIC = 1
        private const val NOW = 2

        private fun js(ctx: Context) = ctx.getSystemService(JobScheduler::class.java)
        private fun cn(ctx: Context) = ComponentName(ctx, RefreshJob::class.java)

        fun schedule(ctx: Context) {
            if (js(ctx).getPendingJob(PERIODIC) != null) return
            js(ctx).schedule(
                JobInfo.Builder(PERIODIC, cn(ctx))
                    .setPeriodic(15 * 60_000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
            )
        }

        fun now(ctx: Context) {
            if (Store.token(ctx) == null) return
            Store.markRefreshing(ctx, true)
            val result = try {
                js(ctx).schedule(
                    JobInfo.Builder(NOW, cn(ctx))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setExpedited(true)
                        .build()
                )
            } catch (e: Exception) {
                JobScheduler.RESULT_FAILURE
            }
            if (result != JobScheduler.RESULT_SUCCESS) {
                js(ctx).schedule(
                    JobInfo.Builder(NOW, cn(ctx)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build()
                )
            }
        }

        fun cancel(ctx: Context) = js(ctx).cancel(PERIODIC)
    }
}
