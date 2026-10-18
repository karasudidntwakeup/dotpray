package dev.karasu.ndotprayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import dev.karasu.ndotprayer.R
import dev.karasu.ndotprayer.data.Prefs
import dev.karasu.ndotprayer.data.PrayerRepository
import dev.karasu.ndotprayer.data.Snapshot
import dev.karasu.ndotprayer.util.Io
import dev.karasu.ndotprayer.util.RefreshJob
import android.util.Log

/**
 * The widget: black panel, NDot countdown (a live [Chronometer]), the next
 * prayer, a segmented bar, and all five times of the day. One size, resizable.
 *
 * The countdown itself runs host-side, so the app's alarms only mark the
 * boundaries: the flip at each prayer (exact, plus a safety re-render) and the
 * two countdown-format switches.
 */
class PrayerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val snapshot = PrayerRepository.snapshot(context)
        val prefs = Prefs.get(context)
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, WidgetRenderer.render(context, id, snapshot, prefs))
        }
        reschedule(context)
        refreshIfNeeded(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        val prefs = Prefs.get(context)
        appWidgetManager.updateAppWidget(
            appWidgetId,
            WidgetRenderer.render(context, appWidgetId, PrayerRepository.snapshot(context), prefs),
        )
    }

    override fun onEnabled(context: Context) {
        RefreshJob.scheduleNow(context)
        RefreshJob.schedulePeriodic(context)
        reschedule(context)
    }

    override fun onDisabled(context: Context) {
        // Last widget removed — nothing left to update.
        AlarmHelper.cancel(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmHelper.ACTION_FLIP -> {
                val widgetManager = AppWidgetManager.getInstance(context)
                val ids = widgetManager.getAppWidgetIds(
                    ComponentName(context, PrayerWidgetProvider::class.java),
                )
                val snapshot = PrayerRepository.snapshot(context)
                val prefs = Prefs.get(context)
                ids.forEach { id ->
                    widgetManager.updateAppWidget(id, WidgetRenderer.render(context, id, snapshot, prefs))
                }
                reschedule(context)
                refreshIfNeeded(context)
            }
            else -> super.onReceive(context, intent)
        }
    }

    /** Heals the cache: the process is alive and network-allowed while the widget
 *  broadcasts run, so the refresh happens right here instead of waiting for a job. */
    private fun refreshIfNeeded(context: Context) {
        if (!PrayerRepository.needsRefresh(context)) return
        Io.io {
            PrayerRepository.refresh(context)
            Io.main { refreshNow(context) }
        }
        // Passive fallback tied to the scheduler, in case this pass failed.
        if (PrayerRepository.needsRefresh(context)) RefreshJob.scheduleNow(context)
    }

    private fun reschedule(context: Context) {
        val next = PrayerRepository.snapshot(context)?.next ?: return
        AlarmHelper.schedule(context, next.timeMillis)
    }

    /** Repaints every placed widget and re-arms the boundary alarm. */
    fun refreshNow(context: Context) {
        WidgetRenderer.push(context)
        reschedule(context)
    }

    companion object {
        const val ACTION_REFRESH = "dev.karasu.ndotprayer.REFRESH"

        fun refreshNow(context: Context) {
            WidgetRenderer.push(context)
            Io.io {
                if (PrayerRepository.needsRefresh(context)) {
                    PrayerRepository.refresh(context)
                    Io.main { WidgetRenderer.push(context) }
                }
            }
        }
    }
}

/** The alarms that keep the widget honest: the flip at the next prayer — set
 *  exact, with a short safety re-render behind it — and the two countdown-format
 *  switches (the 10-hour crossing, then the last hour). */
object AlarmHelper {

    const val ACTION_FLIP = "dev.karasu.ndotprayer.PRAYER_FIRED"

    private const val REQUEST_FLIP = 5151
    private const val REQUEST_HOURS10 = 5152
    private const val REQUEST_HOUR1 = 5153
    private const val REQUEST_SAFETY = 5154

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val GRACE = 2_000L

    /** How far behind the flip the safety re-render sits. */
    private const val SAFETY_DELAY = 2 * MINUTE

    /** Window used when exact alarms are not permitted; the platform floors it at ten minutes anyway. */
    private const val INEXACT_WINDOW = 10 * MINUTE

    fun schedule(context: Context, triggerAtMillis: Long) {
        val manager = context.getSystemService(android.app.AlarmManager::class.java) ?: return
        val remaining = triggerAtMillis - System.currentTimeMillis()

        // The prayer itself, then a short safety net: should the flip broadcast
        // ever be lost, the widget still heals two minutes later — the countdown
        // must never sit stuck below zero, and the bar must reset with it.
        set(manager, context, REQUEST_FLIP, triggerAtMillis)
        set(manager, context, REQUEST_SAFETY, triggerAtMillis + SAFETY_DELAY)

        // Just past the 10-hour crossing: "10:45" must switch to the form
        // that feeds the single-digit-hour precision.
        if (remaining > 10 * HOUR) {
            set(manager, context, REQUEST_HOURS10, triggerAtMillis - 10 * HOUR + GRACE)
        } else {
            cancel(manager, context, REQUEST_HOURS10)
        }

        // Just past the hour crossing: "1:07" must switch to "67 MIN". The
        // grace keeps delivery strictly below one hour.
        if (remaining > HOUR) {
            set(manager, context, REQUEST_HOUR1, triggerAtMillis - HOUR + GRACE)
        } else {
            cancel(manager, context, REQUEST_HOUR1)
        }
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(android.app.AlarmManager::class.java) ?: return
        cancel(manager, context, REQUEST_FLIP)
        cancel(manager, context, REQUEST_HOURS10)
        cancel(manager, context, REQUEST_HOUR1)
        cancel(manager, context, REQUEST_SAFETY)
    }

    /**
     * Exact where the platform allows it — the countdown has to flip at the
     * prayer's own instant, not on a ten-minute inexact grid — with an
     * allow-while-idle inexact window as the fallback. Even waking from doze,
     * allow-while-idle alarms are delivered on time.
     */
    private fun set(
        manager: android.app.AlarmManager,
        context: Context,
        requestCode: Int,
        triggerAtMillis: Long,
    ) {
        val pending = pending(context, requestCode)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        if (exact) {
            manager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pending,
            )
        } else {
            Log.w(TAG, "exact alarms unavailable; scheduling #$requestCode inexact")
            manager.setWindow(
                android.app.AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                INEXACT_WINDOW,
                pending,
            )
        }
    }

    private fun cancel(manager: android.app.AlarmManager, context: Context, requestCode: Int) {
        manager.cancel(pending(context, requestCode))
    }

    private fun pending(context: Context, requestCode: Int): PendingIntent {
        val receiver = Intent(context, PrayerWidgetProvider::class.java).setAction(ACTION_FLIP)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            receiver,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val TAG = "NDotPrayer"
}