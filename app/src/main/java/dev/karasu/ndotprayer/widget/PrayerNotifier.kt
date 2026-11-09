package dev.karasu.ndotprayer.widget

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.RemoteViews
import dev.karasu.ndotprayer.R
import dev.karasu.ndotprayer.data.Format
import dev.karasu.ndotprayer.data.Prayer

/**
 * Posts the prayer-arrival notification in the same Nothing language as the
 * widget: the dot face, one red dot, and monochrome ink. A custom
 * [RemoteViews] body replaces the generic Android title/body so the
 * notification looks like a piece of the widget rather than a system alert.
 */
object PrayerNotifier {

    private const val CHANNEL_ID = "prayer"
    private const val NOTIFICATION_ID = 7101
    private const val REQUEST_REFRESH = 6002

    /** Announce [prayer] if the app is allowed to post notifications. */
    fun notify(context: Context, prayer: Prayer) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!canNotify(context, manager)) return
        ensureChannel(context, manager)

        val views = RemoteViews(context.packageName, R.layout.notification_prayer).apply {
            setTextViewText(R.id.notification_name, prayer.kind.label)
            setTextViewText(R.id.notification_time, Format.clock(prayer.timeMillis))
        }

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.nt_red))
            .setContentTitle(context.getString(R.string.notification_title, prayer.kind.label))
            .setContentText(context.getString(R.string.notification_text, prayer.kind.key))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(prayer.timeMillis)
            .setContentIntent(refreshIntent(context))
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(views)
            .setCustomBigContentView(views)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    /** The channel exists as soon as the app has posted once. */
    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            enableLights(true)
            lightColor = context.getColor(R.color.nt_red)
        }
        manager.createNotificationChannel(channel)
    }

    private fun canNotify(context: Context, manager: NotificationManager): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Tapping the notification refreshes the widget, just like tapping it. */
    private fun refreshIntent(context: Context): PendingIntent {
        val intent = Intent(context, RefreshReceiver::class.java)
            .setAction(RefreshReceiver.ACTION_REFRESH)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_REFRESH,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
