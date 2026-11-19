package dev.karasu.ndotprayer.widget

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

class PrayerCountdownService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prayerTime = intent?.getLongExtra(AlarmHelper.EXTRA_PRAYER_TIME, 0) ?: 0
        val label = intent?.getStringExtra(EXTRA_PRAYER_LABEL)
        val remaining = prayerTime - System.currentTimeMillis()
        if (Build.VERSION.SDK_INT < 36 || label == null ||
            remaining !in 1..AlarmHelper.REMINDER_LEAD
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        handler.removeCallbacksAndMessages(null)
        val manager = getSystemService(NotificationManager::class.java)
        val update = object : Runnable {
            override fun run() {
                val timeLeft = prayerTime - System.currentTimeMillis()
                if (timeLeft <= 0) {
                    stopSelf()
                    return
                }
                manager.notify(PrayerNotifier.REMINDER_ID, notification(label, prayerTime, timeLeft))
                handler.postDelayed(this, minOf(timeLeft, STEP_MILLIS))
            }
        }

        startForeground(
            PrayerNotifier.REMINDER_ID,
            notification(label, prayerTime, remaining),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
        )
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dotpray:PrayerCountdown")
            .apply {
                setReferenceCounted(false)
                acquire(remaining)
            }
        handler.postDelayed(update, minOf(remaining, STEP_MILLIS))
        return START_NOT_STICKY
    }

    @android.annotation.TargetApi(36)
    private fun notification(label: String, prayerTime: Long, remaining: Long): Notification {
        val progress = ((remaining * 100 + AlarmHelper.REMINDER_LEAD - 1) /
            AlarmHelper.REMINDER_LEAD).toInt().coerceIn(1, 100)
        return PrayerNotifier.reminderBuilder(this, label, prayerTime, remaining)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(prayerTime)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .addExtras(Bundle().apply { putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            .setStyle(Notification.ProgressStyle().setProgress(progress))
            .build()
    }

    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        wakeLock?.takeIf { it.isHeld }?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PRAYER_LABEL = "prayer_label"
        private const val STEP_MILLIS = 6_000L
    }
}
