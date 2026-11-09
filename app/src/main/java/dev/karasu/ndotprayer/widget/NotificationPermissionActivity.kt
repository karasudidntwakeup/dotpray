package dev.karasu.ndotprayer.widget

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import dev.karasu.ndotprayer.data.Prefs

/**
 * Invisible prompt for the prayer notification.
 *
 * The app deliberately has no launcher activity, so on Android 13+ there is no
 * way to ask for POST_NOTIFICATIONS at install time. A widget tap opens this
 * transparent activity only while the permission is missing; it shows the
 * system prompt and then disappears. There is no launcher entry.
 */
class NotificationPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        // Asked already (or allowed): just do the refresh the tap was for.
        if (granted || Prefs.get(this).notificationPrompted) {
            refresh()
            return
        }
        Prefs.get(this).notificationPrompted = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    /** Hand the tap back to the normal refresh path, then disappear. */
    private fun refresh() {
        sendBroadcast(
            Intent(this, RefreshReceiver::class.java).setAction(RefreshReceiver.ACTION_REFRESH),
        )
        finish()
    }

    companion object {

        private const val REQUEST = 1

        /** True while a widget tap should open the prompt instead of refreshing directly. */
        fun needsPrompt(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            // Ask once. After a denial, the system app info page is the path back.
            return !Prefs.get(context).notificationPrompted
        }
    }
}
