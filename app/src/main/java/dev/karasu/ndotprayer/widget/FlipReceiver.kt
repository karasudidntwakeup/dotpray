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
            AlarmHelper.ACTION_REMINDER,
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
        if (ids.isEmpty()) return

        val now = System.currentTimeMillis()
        val snapshot = PrayerRepository.snapshot(context, now)
        val prayerTime = intent.getLongExtra(AlarmHelper.EXTRA_PRAYER_TIME, 0)
        if (intent.action == AlarmHelper.ACTION_REMINDER) {
            val next = snapshot?.next ?: return
            val remaining = prayerTime - now
            if (next.timeMillis == prayerTime && remaining in 1..AlarmHelper.REMINDER_LEAD) {
                PrayerNotifier.remind(context, next, remaining)
            }
            return
        }
        ids.forEach { id ->
            manager.updateAppWidget(id, WidgetRenderer.render(context, id, snapshot))
        }

        if (intent.action == AlarmHelper.ACTION_FLIP) {
            val arrived = snapshot?.today?.firstOrNull {
                it.timeMillis == prayerTime && prayerTime <= now
            }
            if (arrived != null) {
                PrayerNotifier.notify(context, arrived)
            }
        }

        PrayerWidgetProvider.reschedule(context, snapshot?.next)
        ThemeJob.schedule(context, replace = true)
        PrayerWidgetProvider.refreshIfNeeded(context)
    }
}
