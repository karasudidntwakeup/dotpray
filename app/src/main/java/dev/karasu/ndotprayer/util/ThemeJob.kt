package dev.karasu.ndotprayer.util

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import dev.karasu.ndotprayer.widget.WidgetRenderer

/**
 * Repaints the widget the moment the system's night setting changes.
 *
 * A provider is not called for a configuration change, and the launcher keeps
 * the last RemoteViews it was handed, so without this the panel would stay on
 * the old theme until the next 30-minute update — or until the widget is moved,
 * which re-renders it as a side effect. The job watches the one setting that
 * changes with the system theme; it is cheap, exact, and there is no polling.
 *
 * Content-trigger jobs cannot be persisted, so boot and package-replaced
 * re-register this job in [dev.karasu.ndotprayer.widget.FlipReceiver].
 */
object ThemeJob {

    private const val JOB_ID = 20503
    private const val NIGHT_SETTING = "ui_night_mode"

    /** The secure setting the system updates when light/dark mode is toggled. */
    private val NIGHT_URI = Settings.Secure.getUriFor(NIGHT_SETTING)

    /**
     * Returns true when the trigger is registered and the next change will wake us.
     *
     * [replace] is for [ThemeWorker] itself: a content-trigger job must re-register
     * the same id when it finishes, which also stops the running instance. Every
     * other caller leaves an already-pending trigger alone.
     */
    fun schedule(context: Context, replace: Boolean = false): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        if (!replace && scheduler.getPendingJob(JOB_ID) != null) return true

        val info = JobInfo.Builder(JOB_ID, ComponentName(context, ThemeWorker::class.java))
            .addTriggerContentUri(JobInfo.TriggerContentUri(NIGHT_URI, 0))
            .setTriggerContentUpdateDelay(0L)
            .setTriggerContentMaxDelay(0L)
            .build()
        return scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    /**
     * The job can be delivered to a process that was frozen before the system
     * changed its configuration, so don't trust `resources` alone: when the
     * user has picked light or dark explicitly, build a context for that mode.
     * Auto/custom stay on the process configuration (the 30-minute repaint
     * covers those, since no setting is written at the transition).
     */
    fun themed(context: Context): Context {
        val mode = Settings.Secure.getInt(context.contentResolver, NIGHT_SETTING, 0)
        if (mode != 1 && mode != 2) return context

        val config = Configuration(context.resources.configuration)
        val mask = Configuration.UI_MODE_NIGHT_MASK
        val night = if (mode == 2) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        config.uiMode = (config.uiMode and mask.inv()) or night
        return context.createConfigurationContext(config)
    }
}

/**
 * One theme change, one repaint. Re-registering the same job id in [onStartJob]
 * is the documented way to keep a content trigger alive (it replaces
 * `jobFinished`); the current job stops once the new one is in place.
 */
class ThemeWorker : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        WidgetRenderer.push(ThemeJob.themed(this))
        // Re-registering the same id replaces this run; the next setting change
        // starts a fresh one. No getPendingJob shortcut here — the running job
        // would count as pending and we would never watch the next change.
        if (ThemeJob.schedule(this, replace = true)) return true
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
