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
import kotlin.coroutines.resume

private const val PREFS_NAME  = "weather_cache"
private const val KEY_TEMP    = "temp"
private const val KEY_CODE    = "code"
private const val KEY_FETCHED = "fetched_at"
private const val KEY_CITY    = "city"
private const val OPEN_METEO  =
    "https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code"

// --- Open-Meteo DTOs ---
private data class OpenMeteoResponse(val current: CurrentBlock? = null)
private data class CurrentBlock(
    @SerializedName("temperature_2m") val temperature: Float = 0f,
    @SerializedName("weather_code")   val weatherCode: Int  = 0,
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
