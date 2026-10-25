package dev.karasu.ndotprayer.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.karasu.ndotprayer.util.RefreshJob

/**
 * A widget tap: show the loading state immediately, then let [RefreshJob] do
 * the network work in the background. Unexported — the launcher reaches it
 * through the PendingIntent attached to the widget.
 */
class RefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REFRESH) return
        WidgetRenderer.pushLoading(context)
        if (!RefreshJob.scheduleManual(context)) WidgetRenderer.push(context)
    }

    companion object {
        const val ACTION_REFRESH = "dev.karasu.ndotprayer.REFRESH"
    }
}
