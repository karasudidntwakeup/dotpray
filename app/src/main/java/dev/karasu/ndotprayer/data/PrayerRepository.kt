package dev.karasu.ndotprayer.data

import android.content.Context
import android.util.Log
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Cached prayer data plus the "what should the widget show right now" logic.
 *
 * Reads are synchronous and cheap (SharedPreferences); [refresh] does the network
 * work and must be called off the main thread.
 */
object PrayerRepository {

    fun zone(): ZoneId = ZoneId.systemDefault()

    /** Null when nothing is cached yet — the widget shows a placeholder and refreshes. */
    fun snapshot(context: Context, nowMillis: Long = System.currentTimeMillis()): Snapshot? {
        val prefs = Prefs.get(context)
        val date = Instant.ofEpochMilli(nowMillis).atZone(zone()).toLocalDate()
        val prayers = prefs.getDay(date)?.prayers?.sortedBy { it.timeMillis } ?: return null
        val next = prayers.firstOrNull { it.timeMillis > nowMillis }
            ?: prefs.getDay(date.plusDays(1))?.prayers?.firstOrNull { it.kind == PrayerKind.FAJR }
        return Snapshot(next = next, today = prayers)
    }

    /** True when the cache is missing, expired, or has no entry for tomorrow. */
    fun needsRefresh(context: Context, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val prefs = Prefs.get(context)
        val zone = zone()
        val date = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        if (prefs.cachedSignature() != prefs.signature(zone.id)) return true
        val today = prefs.getDay(date) ?: return true
        if (today.fetchedAtMillis > nowMillis) return true
        return (nowMillis - today.fetchedAtMillis) > DayTimings.STALE_AFTER_MILLIS ||
            prefs.getDay(date.plusDays(1)) == null
    }

    /**
     * Refreshes the timings for the stored location. Location detection only
     * happens on demand ([relocate]) or once on first setup, so background
     * refreshes never move the widget.
     */
    @Synchronized
    fun refresh(context: Context, relocate: Boolean = false): Boolean {
        val prefs = Prefs.get(context)
        val zone = zone()
        val date = LocalDate.now(zone)
        val location = if (relocate || !prefs.hasLocation) LocationDetector.detect() else null
        val latitude = location?.first ?: prefs.latitude
        val longitude = location?.second ?: prefs.longitude

        val today = fetch(date, zone, latitude, longitude) ?: return false
        val tomorrow = fetch(date.plusDays(1), zone, latitude, longitude)
        prefs.storeTimings(latitude, longitude, location != null, zone.id, today, tomorrow)
        return true
    }

    private fun fetch(date: LocalDate, zone: ZoneId, latitude: Double, longitude: Double): DayTimings? {
        return try {
            val raw = AladhanApi.timings(
                date = date,
                latitude = latitude,
                longitude = longitude,
                method = Prefs.DEFAULT_METHOD,
                school = Prefs.DEFAULT_SCHOOL,
                timeZoneId = zone.id,
            )
            DayTimings(
                date = date,
                prayers = PrayerKind.ORDERED.map { kind ->
                    val time = raw.times.getValue(kind)
                    Prayer(kind, date.atTime(time).atZone(zone).toInstant().toEpochMilli())
                },
                fetchedAtMillis = System.currentTimeMillis(),
            )
        } catch (error: Exception) {
            Log.w(TAG, "fetch $date failed: ${error.javaClass.simpleName}: ${error.message}")
            null
        }
    }

    private const val TAG = "NDotPrayer"
}