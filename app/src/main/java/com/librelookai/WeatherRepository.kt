package com.librelookai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import androidx.core.content.ContextCompat
import java.util.Locale
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.coroutines.resume

private const val PREFS_NAME  = "weather_cache"
private const val KEY_TEMP    = "temp"
private const val KEY_CODE    = "code"
private const val KEY_FETCHED = "fetched_at"
private const val KEY_CITY    = "city"
private const val OPEN_METEO  =
    "https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code"
private const val GEO_API =
    "https://geocoding-api.open-meteo.com/v1/search"
private const val FORECAST_API =
    "https://api.open-meteo.com/v1/forecast"
private const val ARCHIVE_API =
    "https://archive-api.open-meteo.com/v1/archive"
/** Maximum number of days Open-Meteo's live forecast covers (inclusive of today). */
private const val FORECAST_HORIZON_DAYS = 15L

// --- Open-Meteo DTOs ---
private data class OpenMeteoResponse(val current: CurrentBlock? = null)
private data class CurrentBlock(
    @SerializedName("temperature_2m") val temperature: Float = 0f,
    @SerializedName("weather_code")   val weatherCode: Int  = 0,
)
private data class GeoResponse(val results: List<GeoResult>? = null)
private data class GeoResult(
    val name: String = "",
    val country: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
)
private data class ForecastResponse(val daily: DailyBlock? = null)
private data class DailyBlock(
    val time: List<String> = emptyList(),
    @SerializedName("temperature_2m_max") val maxTemps: List<Float> = emptyList(),
    @SerializedName("temperature_2m_min") val minTemps: List<Float> = emptyList(),
    @SerializedName("weathercode")        val weatherCodes: List<Int> = emptyList(),
)

class WeatherRepository(private val context: Context) {

    private val http  = OkHttpClient()
    private val gson  = com.google.gson.Gson()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Loads the cached reading (may be stale). Returns null if nothing cached yet. */
    fun loadCached(): WeatherData? {
        val temp    = prefs.getFloat(KEY_TEMP, Float.MIN_VALUE)
        val code    = prefs.getInt(KEY_CODE, -1)
        val fetched = prefs.getLong(KEY_FETCHED, 0L)
        val city    = prefs.getString(KEY_CITY, "") ?: ""
        if (temp == Float.MIN_VALUE || code < 0) return null
        return WeatherData(temp, code, fetched, city)
    }

    /**
     * Fetches a fresh reading from Open-Meteo using the device's last known coarse location.
     * Returns null if location permission is not granted or location is unavailable.
     */
    suspend fun fetchFresh(): WeatherData? = withContext(Dispatchers.IO) {
        val location = getCoarseLocation() ?: return@withContext null
        val url = "$OPEN_METEO&latitude=${location.first}&longitude=${location.second}"
        return@withContext try {
            val body = http.newCall(Request.Builder().url(url).build()).await().body?.string()
                ?: return@withContext null
            val resp = gson.fromJson(body, OpenMeteoResponse::class.java)
            val block = resp.current ?: return@withContext null
            val city = getCityName(location.first, location.second)
            WeatherData(block.temperature, block.weatherCode, cityName = city).also { cache(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun cache(data: WeatherData) {
        prefs.edit()
            .putFloat(KEY_TEMP, data.temperatureCelsius)
            .putInt(KEY_CODE, data.weatherCode)
            .putLong(KEY_FETCHED, data.fetchedAt)
            .putString(KEY_CITY, data.cityName)
            .apply()
    }

    /**
     * Geocodes [destination] then fetches weather data for [days] days starting on [startDate].
     *
     * - If the trip falls within Open-Meteo's live forecast window (today + 15 days), the real
     *   forecast is returned.
     * - Otherwise the archive API is queried for the same calendar period from the previous year
     *   (or the actual past dates if the trip is already in the past), and the result is flagged
     *   as historical so the UI can inform the user.
     *
     * Returns [ForecastResult] or null on any failure.
     */
    suspend fun fetchDestinationForecast(
        destination: String,
        days: Int,
        startDate: LocalDate,
    ): ForecastResult? = withContext(Dispatchers.IO) {
        try {
            // Step 1 — Geocode
            val encoded = java.net.URLEncoder.encode(destination, "UTF-8")
            val geoBody = http.newCall(
                Request.Builder().url("$GEO_API?name=$encoded&count=1&language=en&format=json").build(),
            ).await().body?.string() ?: return@withContext null

            val geoResp = gson.fromJson(geoBody, GeoResponse::class.java)
            val geo = geoResp.results?.firstOrNull() ?: return@withContext null
            val resolvedName = if (geo.country.isNotEmpty()) "${geo.name}, ${geo.country}" else geo.name

            val today   = LocalDate.now()
            val endDate = startDate.plusDays((days - 1).toLong())
            val forecastCutoff = today.plusDays(FORECAST_HORIZON_DAYS)

            // Step 2 — Decide source: live forecast or historical archive
            val isHistorical = startDate.isAfter(forecastCutoff) || endDate.isBefore(today)

            val daily: DailyBlock
            val referenceYear: Int?

            if (!isHistorical) {
                // Live forecast — request enough days from today to cover the whole trip
                val daysFromToday = (ChronoUnit.DAYS.between(today, endDate) + 1)
                    .coerceIn(1, 16).toInt()
                val body = http.newCall(
                    Request.Builder().url(
                        "$FORECAST_API?latitude=${geo.latitude}&longitude=${geo.longitude}" +
                            "&daily=temperature_2m_max,temperature_2m_min,weathercode" +
                            "&forecast_days=$daysFromToday&timezone=auto",
                    ).build(),
                ).await().body?.string() ?: return@withContext null
                daily = gson.fromJson(body, ForecastResponse::class.java).daily
                    ?: return@withContext null
                referenceYear = null
            } else {
                // Historical archive — use previous year for future trips, actual dates for past
                val refStart: LocalDate
                val refEnd: LocalDate
                if (endDate.isBefore(today)) {
                    // Trip already happened — use the real archive dates
                    refStart = startDate
                    refEnd   = endDate
                    referenceYear = startDate.year
                } else {
                    // Future trip beyond forecast window — reference the same period last year
                    refStart = startDate.minusYears(1)
                    refEnd   = endDate.minusYears(1)
                    referenceYear = refStart.year
                }
                val body = http.newCall(
                    Request.Builder().url(
                        "$ARCHIVE_API?latitude=${geo.latitude}&longitude=${geo.longitude}" +
                            "&start_date=$refStart&end_date=$refEnd" +
                            "&daily=temperature_2m_max,temperature_2m_min,weathercode" +
                            "&timezone=auto",
                    ).build(),
                ).await().body?.string() ?: return@withContext null
                daily = gson.fromJson(body, ForecastResponse::class.java).daily
                    ?: return@withContext null
            }

            // Step 3 — Map daily blocks to DayForecast, re-labelling archive dates to trip dates
            val forecasts = daily.time.indices.map { i ->
                val date = if (isHistorical) {
                    // Shift archive date forward to the actual trip date for display
                    startDate.plusDays(i.toLong()).toString()
                } else {
                    daily.time.getOrElse(i) { "" }
                }
                DayForecast(
                    date        = date,
                    minTempC    = daily.minTemps.getOrElse(i) { 0f },
                    maxTempC    = daily.maxTemps.getOrElse(i) { 0f },
                    weatherCode = daily.weatherCodes.getOrElse(i) { 0 },
                )
            }.filter { it.date.isNotEmpty() }
                // For live forecast, keep only days within the requested trip window
                .let { list ->
                    if (!isHistorical) list.filter { !LocalDate.parse(it.date).isBefore(startDate) }
                    else list
                }

            ForecastResult(
                resolvedName  = resolvedName,
                forecasts     = forecasts.take(days),
                isHistorical  = isHistorical,
                referenceYear = referenceYear,
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Reverse-geocodes [lat]/[lon] to a city name using the system Geocoder. */
    private fun getCityName(lat: Double, lon: Double): String = try {
        @Suppress("DEPRECATION")
        Geocoder(context, Locale.ENGLISH).getFromLocation(lat, lon, 1)
            ?.firstOrNull()
            ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
            ?: ""
    } catch (_: Exception) { "" }

    /** Returns (latitude, longitude) or null when permission is missing or location unavailable. */
    private suspend fun getCoarseLocation(): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return null

        val client = LocationServices.getFusedLocationProviderClient(context)

        // Always request a fresh location — lastLocation can be arbitrarily old (days, different
        // country) and is not reliable for current weather. Network/WiFi fix is fast and cheap.
        val cts = CancellationTokenSource()
        val current = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cts.cancel() }
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
                .addOnCanceledListener  { cont.resume(null) }
        }
        if (current != null) return current.latitude to current.longitude

        // Last resort: accept lastLocation only if it is younger than 1 hour
        val last = suspendCancellableCoroutine { cont ->
            client.lastLocation
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
                .addOnCanceledListener  { cont.resume(null) }
        }
        val oneHourMs = 60 * 60 * 1000L
        return last
            ?.takeIf { System.currentTimeMillis() - it.time < oneHourMs }
            ?.let { it.latitude to it.longitude }
    }
}
