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

/** One cached calendar day of timings. */
data class DayTimings(
    val date: LocalDate,
    val prayers: List<Prayer>,
    val fetchedAtMillis: Long,
) {
    companion object {
        const val STALE_AFTER_MILLIS = 12L * 60 * 60 * 1000
    }
}

/** Everything the widget needs for one render pass. */
data class Snapshot(
    val next: Prayer,
    val today: List<Prayer>,
)

/** Pure formatting helpers shared by the widget and the renderer. */
object Format {

    fun clock(millis: Long): String =
        DateTimeFormatter.ofPattern("HH:mm", Locale.US).format(
            java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()),
        )
}