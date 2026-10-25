package dev.karasu.ndotprayer.util

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import dev.karasu.ndotprayer.data.PrayerRepository
import dev.karasu.ndotprayer.widget.PrayerWidgetProvider
import dev.karasu.ndotprayer.widget.WidgetRenderer

private const val EXTRA_RELOCATE = "relocate"

/**
 * Data refetch runs through [JobScheduler] on purpose.
 *
 * Nothing's background firewall blocks normal socket access for apps that sit
 * in the background (like a widget-only app), so broadcasts alone lose the race.
 * A job with a network constraint gets explicit allow while it runs, only
 * executes when data is reachable, and survives reboots when persisted.
 */
object RefreshJob {

    private const val JOB_NOW = 20501
    private const val JOB_PERIODIC = 20502
    private const val JOB_MANUAL = 20504

    /** Refetch soon, only when the widget actually needs it. */
    fun scheduleNow(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val info = JobInfo.Builder(JOB_NOW, ComponentName(context, RefreshWorker::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(2_000L)
            .setOverrideDeadline(60_000L)
            .build()
        scheduler.cancel(JOB_NOW)
        scheduler.schedule(info)
    }

    /** A widget tap: always fetch, and re-detect the location once. */
    fun scheduleManual(context: Context): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val extras = PersistableBundle().apply { putBoolean(EXTRA_RELOCATE, true) }
        val info = JobInfo.Builder(JOB_MANUAL, ComponentName(context, RefreshWorker::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(0L)
            .setOverrideDeadline(30_000L)
            .setExtras(extras)
            .build()
        scheduler.cancel(JOB_MANUAL)
        return scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
    }

    /** Hourly keeper so the countdown survives midnight and offline days. */
    fun schedulePeriodic(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val info = JobInfo.Builder(JOB_PERIODIC, ComponentName(context, RefreshWorker::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(60 * 60 * 1000L)
            .setPersisted(true)
            .build()
        scheduler.cancel(JOB_PERIODIC)
        scheduler.schedule(info)
    }

    fun cancel(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        scheduler.cancel(JOB_NOW)
        scheduler.cancel(JOB_PERIODIC)
        scheduler.cancel(JOB_MANUAL)
    }
}

/** The single worker behind both jobs: refresh if needed, repaint, re-arm. */
class RefreshWorker : JobService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onStartJob(params: JobParameters?): Boolean {
        val context: Context = this
        val relocate = params?.extras?.getBoolean(EXTRA_RELOCATE, false) == true
        Thread {
            try {
                if (relocate || PrayerRepository.needsRefresh(context)) {
                    PrayerRepository.refresh(context, relocate = relocate)
                }
            } finally {
                mainHandler.post {
                    WidgetRenderer.push(context)
                    PrayerWidgetProvider.reschedule(context)
                    try {
                        jobFinished(params, PrayerRepository.needsRefresh(context))
                    } catch (_: Exception) {
                        // Job already finished or cancelled — nothing to do.
                    }
                }
            }
        }.apply { isDaemon = true }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}