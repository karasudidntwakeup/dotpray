package dev.karasu.ndotprayer.data

import android.content.Context
import android.util.Log
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

    fun today(): LocalDate = LocalDate.now(zone())

    /** Null when nothing is cached yet — the widget shows a placeholder and refreshes. */
    fun snapshot(context: Context, nowMillis: Long = System.currentTimeMillis()): Snapshot? {
        val prefs = Prefs.get(context)
        val zone = zone()
        val date = LocalDate.now(zone)

        val today = prefs.getDay(date) ?: return null
        val tomorrow = prefs.getDay(date.plusDays(1))

        val timeline = buildList {
            addAll(today.prayers.sortedBy { it.timeMillis })
            tomorrow?.prayers?.firstOrNull { it.kind == PrayerKind.FAJR }?.let { add(it) }
        }
        if (timeline.isEmpty()) return null

        val nextIndex = timeline.indexOfFirst { it.timeMillis > nowMillis }
        val next = when {
            nextIndex >= 0 -> timeline[nextIndex]
            // Every cached prayer is in the past (stale cache with no tomorrow).
            else -> timeline.last()
        }

        return Snapshot(
            next = next,
            today = today.prayers.sortedBy { it.timeMillis },
        )
    }

    /** True when the cache is missing, expired, or has no entry for tomorrow. */
    fun needsRefresh(context: Context, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val prefs = Prefs.get(context)
        val zone = zone()
        val date = LocalDate.now(zone)
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
    fun refresh(context: Context, relocate: Boolean = false): Boolean {
        val prefs = Prefs.get(context)
        val zone = zone()

        if (relocate || !prefs.hasLocation) {
            LocationDetector.detect()?.let { (lat, lon) ->
                val changed = lat != prefs.latitude || lon != prefs.longitude
                // Persist the fix even when it matches the default, so later
                // background refreshes stay local-only.
                prefs.latitude = lat
                prefs.longitude = lon
                if (changed) prefs.clearCache()
            }
        }

        val date = LocalDate.now(zone)
        val todayFetched = fetchAndStore(context, date) == true
        // Tomorrow is a nice-to-have: without it the widget still works today.
        fetchAndStore(context, date.plusDays(1))

        prefs.storeSignature(prefs.signature(zone.id))
        return todayFetched
    }

    private fun fetchAndStore(context: Context, date: LocalDate): Boolean {
        val prefs = Prefs.get(context)
        return try {
            val raw = AladhanApi.timings(
                date = date,
                latitude = prefs.latitude,
                longitude = prefs.longitude,
                method = Prefs.DEFAULT_METHOD,
                school = Prefs.DEFAULT_SCHOOL,
                timeZoneId = zone().id,
            )
            prefs.putDay(
                DayTimings(
                    date = date,
                    prayers = PrayerKind.ORDERED.map { kind ->
                        val time = raw.times.getValue(kind)
                        Prayer(
                            kind = kind,
                            timeMillis = java.time.ZonedDateTime.of(date, time, zone())
                                .toInstant()
                                .toEpochMilli(),
                        )
                    },
                    fetchedAtMillis = System.currentTimeMillis(),
                ),
            )
            true
        } catch (error: Exception) {
            Log.w(TAG, "fetch $date failed: ${error.javaClass.simpleName}: ${error.message}")
            false
        }
    }

    private const val TAG = "NDotPrayer"
}