package dev.karasu.ndotprayer.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.karasu.ndotprayer.data.PrayerRepository
import dev.karasu.ndotprayer.util.ThemeJob

/**
 * The app's own boundary wakeups: the flip at each prayer and the two
 * countdown-format switches. Also re-arms them after a reboot or an app update,
 * since the platform drops pending alarms on both.
 *
 * Deliberately unexported, and deliberately separate from
 * [PrayerWidgetProvider] — that receiver has to stay exported for the system's
 * APPWIDGET_UPDATE, so keeping the alarms in it would let any installed app
 * fire them at will. These arrive as the app's own [android.app.PendingIntent]s.
 */
class FlipReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmHelper.ACTION_ANNOUNCE_END -> {
                WidgetRenderer.endAnnouncement(context)
                return
            }
            AlarmHelper.ACTION_FLIP,
            AlarmHelper.ACTION_FLIP_SAFETY,
            AlarmHelper.ACTION_FORMAT,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }

        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            ComponentName(context, PrayerWidgetProvider::class.java),
        )
        val snapshot = PrayerRepository.snapshot(context)
        ids.forEach { id ->
            manager.updateAppWidget(id, WidgetRenderer.render(context, id, snapshot))
        }

        if (intent.action == AlarmHelper.ACTION_FLIP) {
            // The alarm fires at (or a hair after) the prayer itself: the latest
            // time in today's list is the prayer that just arrived.
            val arrived = snapshot?.today?.lastOrNull {
                it.timeMillis <= System.currentTimeMillis() + 5_000L
            }
            if (arrived != null) {
                WidgetRenderer.announce(context, arrived.kind)
                AlarmHelper.scheduleAnnouncementEnd(
                    context,
                    WidgetRenderer.ANNOUNCEMENT_DURATION_MS,
                )
            }
        }

        PrayerWidgetProvider.reschedule(context)
        ThemeJob.schedule(context, replace = true)
        PrayerWidgetProvider.refreshIfNeeded(context)
    }
}
