package com.librelookai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
        if (temp == Float.MIN_VALUE || code < 0) return null
        return WeatherData(temp, code, fetched)
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
            WeatherData(block.temperature, block.weatherCode).also { cache(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun cache(data: WeatherData) {
        prefs.edit()
            .putFloat(KEY_TEMP, data.temperatureCelsius)
            .putInt(KEY_CODE, data.weatherCode)
            .putLong(KEY_FETCHED, data.fetchedAt)
            .apply()
    }

    /** Returns (latitude, longitude) or null when permission is missing or location unavailable. */
    private suspend fun getCoarseLocation(): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return null

        val client = LocationServices.getFusedLocationProviderClient(context)

        // Try last-known location first (free, instant)
        val last = suspendCancellableCoroutine { cont ->
            client.lastLocation
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
                .addOnCanceledListener  { cont.resume(null) }
        }
        if (last != null) return last.latitude to last.longitude

        // Fall back to a single current-location request (uses network/WiFi, not GPS)
        val cts = CancellationTokenSource()
        val current = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cts.cancel() }
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
                .addOnCanceledListener  { cont.resume(null) }
        }
        return current?.let { it.latitude to it.longitude }
    }
}
