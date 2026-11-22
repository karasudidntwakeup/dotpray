package dev.karasu.ndotprayer.data

import android.content.Context
import java.time.LocalDate

/**
 * Location, calculation defaults and the timings cache.
 *
 * Default location mirrors the desktop `prayer.sh` (Berlin); auto-detection via
 * IP keeps it honest while travelling, with no runtime location permission.
 */
object Prefs {

    private const val FILE = "ndot_prayer"

    const val DEFAULT_LATITUDE = 52.5200
    const val DEFAULT_LONGITUDE = 13.4050
    const val DEFAULT_METHOD = 3 // Muslim World League
    const val DEFAULT_SCHOOL = 1 // Hanafi

    private const val KEY_LAT = "latitude"
    private const val KEY_LON = "longitude"
    private const val KEY_SIGNATURE = "cache_signature"
    private const val KEY_NOTIFICATION_PROMPTED = "notification_prompted"
    private const val CACHE_DAY_PREFIX = "day_"
    private const val CACHE_META_PREFIX = "day_meta_"

    fun get(context: Context): Store = Store(context.applicationContext)

    class Store internal constructor(private val context: Context) {

        private val prefs get() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        val latitude: Double
            get() = doubleOf(prefs.getLong(KEY_LAT, DEFAULT_LATITUDE_BITS))

        val longitude: Double
            get() = doubleOf(prefs.getLong(KEY_LON, DEFAULT_LONGITUDE_BITS))

        /** False until a location has been detected and stored at least once. */
        val hasLocation: Boolean
            get() = prefs.contains(KEY_LAT) && prefs.contains(KEY_LON)

        /** True once the notification permission prompt has actually been shown. */
        var notificationPrompted: Boolean
            get() = prefs.getBoolean(KEY_NOTIFICATION_PROMPTED, false)
            set(value) = prefs.edit().putBoolean(KEY_NOTIFICATION_PROMPTED, value).apply()

        fun signature(timeZoneId: String, lat: Double = latitude, lon: Double = longitude): String =
            "${bitsOf(lat)}_${bitsOf(lon)}_${DEFAULT_METHOD}_${DEFAULT_SCHOOL}_$timeZoneId"

        fun cachedSignature(): String? = prefs.getString(KEY_SIGNATURE, null)

        fun storeTimings(
            lat: Double,
            lon: Double,
            locationDetected: Boolean,
            timeZoneId: String,
            today: DayTimings,
            tomorrow: DayTimings?,
        ) {
            val signature = signature(timeZoneId, lat, lon)
            val sameLocation = signature == cachedSignature()
            val datesToKeep = setOf(today.date, today.date.plusDays(1)).mapTo(hashSetOf()) { it.toString() }
            val editor = prefs.edit()
            for (key in prefs.all.keys) {
                val date = when {
                    key.startsWith(CACHE_META_PREFIX) -> key.removePrefix(CACHE_META_PREFIX)
                    key.startsWith(CACHE_DAY_PREFIX) -> key.removePrefix(CACHE_DAY_PREFIX)
                    else -> continue
                }
                if (!sameLocation || date !in datesToKeep) editor.remove(key)
            }
            if (locationDetected) {
                editor.putLong(KEY_LAT, bitsOf(lat)).putLong(KEY_LON, bitsOf(lon))
            }
            editor.putString(KEY_SIGNATURE, signature)
            for (day in listOfNotNull(today, tomorrow)) {
                val payload = day.prayers.joinToString(";") { "${it.kind.key}=${it.timeMillis}" }
                editor.putString(CACHE_DAY_PREFIX + day.date, payload)
                editor.putLong(CACHE_META_PREFIX + day.date, day.fetchedAtMillis)
            }
            editor.apply()
        }

        fun getDay(date: LocalDate): DayTimings? {
            val payload = prefs.getString(CACHE_DAY_PREFIX + date, null) ?: return null
            val prayers = payload.split(";").mapNotNull { entry ->
                val parts = entry.split("=", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val kind = PrayerKind.entries.firstOrNull { it.key == parts[0] }
                    ?: return@mapNotNull null
                val millis = parts[1].toLongOrNull() ?: return@mapNotNull null
                Prayer(kind, millis)
            }
            if (prayers.map { it.kind }.toSet().size != PrayerKind.entries.size) return null
            return DayTimings(
                date = date,
                prayers = prayers,
                fetchedAtMillis = prefs.getLong(CACHE_META_PREFIX + date, 0L),
            )
        }
    }
}

private fun bitsOf(value: Double): Long = java.lang.Double.doubleToRawLongBits(value)

private fun doubleOf(bits: Long): Double = java.lang.Double.longBitsToDouble(bits)

private val DEFAULT_LATITUDE_BITS = bitsOf(Prefs.DEFAULT_LATITUDE)
private val DEFAULT_LONGITUDE_BITS = bitsOf(Prefs.DEFAULT_LONGITUDE)