package dev.karasu.ndotprayer.data

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The five daily prayers, in canonical order. */
enum class PrayerKind(val key: String, val label: String) {
    FAJR("Fajr", "FAJR"),
    DHUHR("Dhuhr", "DHUHR"),
    ASR("Asr", "ASR"),
    MAGHRIB("Maghrib", "MAGHRIB"),
    ISHA("Isha", "ISHA");

    companion object {
        val ORDERED: List<PrayerKind> = entries.toList()
    }
}

/** A single prayer instant, already converted to absolute epoch time. */
data class Prayer(
    val kind: PrayerKind,
    val timeMillis: Long,
)

/** One cached calendar day of timings plus its Hijri label. */
data class DayTimings(
    val date: LocalDate,
    val prayers: List<Prayer>,
    val hijri: String,
    val fetchedAtMillis: Long,
) {
    val isStale: Boolean
        get() = System.currentTimeMillis() - fetchedAtMillis > STALE_AFTER_MILLIS

    companion object {
        const val STALE_AFTER_MILLIS = 12L * 60 * 60 * 1000
    }
}

/**
 * Everything the widget needs for one render pass.
 *
 * [progress] is the fraction of the window that has elapsed between the previous
 * prayer and the next one — that drives the segmented bar.
 */
data class Snapshot(
    val previous: Prayer?,
    val next: Prayer,
    val progress: Float,
    val today: List<Prayer>,
    val hijri: String,
)

/** Pure formatting helpers shared by the widget and the renderer. */
object Format {

    fun clock(millis: Long): String =
        DateTimeFormatter.ofPattern("HH:mm", Locale.US).format(
            java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()),
        )

    /** "SAT 18 OCT" — the Nothing-style metadata line. */
    fun dayStamp(date: LocalDate): String =
        DateTimeFormatter.ofPattern("EEE dd MMM", Locale.US)
            .format(date)
            .uppercase(Locale.US)
}