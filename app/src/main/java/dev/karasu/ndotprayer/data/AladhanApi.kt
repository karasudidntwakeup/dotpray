package dev.karasu.ndotprayer.data

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Raw, already-parsed timings for one date as returned by Aladhan. */
data class RawTimings(
    val times: Map<PrayerKind, LocalTime>,
    val hijri: String,
)

/**
 * Aladhan client. No API key, no SDK — the same free endpoint the desktop
 * `prayer.sh` already uses.
 *
 * Times are requested in the phone's own timezone (`timezonestring`) so the
 * values we store are directly comparable with `System.currentTimeMillis()`,
 * even when the device sits in a different timezone than the prayer location.
 */
object AladhanApi {

    private const val BASE = "https://api.aladhan.com/v1/timings/"
    private const val DATE_FORMAT = "dd-MM-yyyy"

    private val HIJRI_MONTHS = listOf(
        "MUHARRAM", "SAFAR", "RABI'I", "RABI'II",
        "JUMADA I", "JUMADA II", "RAJAB", "SHA'BAN",
        "RAMADHAN", "SHAWAL", "DHU QI'DAH", "DHU HIJJA",
    )

    fun timings(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        method: Int,
        school: Int,
        timeZoneId: String,
    ): RawTimings {
        val formatted = date.format(DateTimeFormatter.ofPattern(DATE_FORMAT, Locale.US))
        val zone = URLEncoder.encode(timeZoneId, "UTF-8")
        val url = URL(
            "$BASE$formatted?latitude=$latitude&longitude=$longitude" +
                "&method=$method&school=$school&timezonestring=$zone",
        )

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "ndot-prayer/1.0 (Android)")
        }

        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                throw ApiException("HTTP $code from Aladhan")
            }
            return parse(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(body: String): RawTimings {
        val root = JSONObject(body)
        if (root.optInt("code", -1) != 200) {
            throw ApiException(root.optString("status", "unexpected response"))
        }
        val data = root.getJSONObject("data")
        val timings = data.getJSONObject("timings")

        val times = PrayerKind.ORDERED.mapNotNull { kind ->
            // Aladhan returns "05:43 (EEST)" — the zone suffix is noise for us.
            val raw = timings.optString(kind.key).trim()
            if (raw.isEmpty()) return@mapNotNull null
            val clock = raw.substringBefore(' ').trim()
            val parsed = runCatching { LocalTime.parse(clock) }.getOrNull()
                ?: return@mapNotNull null
            kind to parsed
        }.toMap()

        if (times.size != PrayerKind.ORDERED.size) {
            throw ApiException("incomplete timings in response")
        }
        return RawTimings(times = times, hijri = parseHijri(data))
    }

    private fun parseHijri(data: JSONObject): String {
        return runCatching {
            val dateContainers = listOf(
                runCatching { data.getJSONObject("date") },
                runCatching { data.getJSONObject("meta").getJSONObject("date") },
            )
            val hijri = dateContainers.asSequence()
                .mapNotNull { it.getOrNull() }
                .firstNotNullOfOrNull { runCatching { it.getJSONObject("hijri") }.getOrNull() }
                ?: return@runCatching ""
            val day = hijri.optString("day").removePrefix("0").trim()
            val monthNumber = hijri.getJSONObject("month").optInt("number", 0)
            val year = hijri.optString("year").trim()
            val monthName = HIJRI_MONTHS.getOrNull(monthNumber - 1) ?: "—"
            "$day $monthName $year"
        }.getOrDefault("")
    }

    class ApiException(message: String) : java.io.IOException(message)
}

    /** Coarse IP geolocation by HTTPS, with no runtime location permission or API key. */
    object LocationDetector {

        fun detect(): Pair<Double, Double>? = runCatching {
            val url = URL("https://ipwho.is/")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "ndot-prayer/1.0 (Android)")
            }
            try {
                if (connection.responseCode !in 200..299) return null
                val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(body)
                if (!json.optBoolean("success", false)) return null
                val lat = json.getDouble("latitude")
                val lon = json.getDouble("longitude")
                if (lat.isNaN() || lon.isNaN()) null else lat to lon
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }