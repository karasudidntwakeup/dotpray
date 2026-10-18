package dev.karasu.ndotprayer.util

import android.os.Handler
import android.os.Looper

/** One daemon thread for network calls plus a main-thread poster for widget repaints. */
object Io {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun io(block: () -> Unit) {
        Thread {
            try {
                block()
            } catch (_: Exception) {
                // Widget rendering must never crash on a transient network failure.
            }
        }.apply { isDaemon = true }.start()
    }

    fun main(block: () -> Unit) {
        mainHandler.post { block() }
    }
}