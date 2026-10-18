package dev.karasu.ndotprayer.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.karasu.ndotprayer.data.Prefs
import dev.karasu.ndotprayer.data.PrayerRepository

/**
 * The app's own boundary wakeups: the flip at each prayer and the two
 * countdown-format switches.
 *
 * Deliberately unexported, and deliberately separate from
 * [PrayerWidgetProvider] — that receiver has to stay exported for the system's
 * APPWIDGET_UPDATE, so keeping the alarms in it would let any installed app
 * fire them at will. These arrive as the app's own [android.app.PendingIntent]s.
 */
class FlipReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmHelper.ACTION_FLIP) return

        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            ComponentName(context, PrayerWidgetProvider::class.java),
        )
        val snapshot = PrayerRepository.snapshot(context)
        val prefs = Prefs.get(context)
        ids.forEach { id ->
            manager.updateAppWidget(id, WidgetRenderer.render(context, id, snapshot, prefs))
        }
        PrayerWidgetProvider.reschedule(context)
        PrayerWidgetProvider.refreshIfNeeded(context)
    }
}
