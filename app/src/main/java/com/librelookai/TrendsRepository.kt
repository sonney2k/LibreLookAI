package com.librelookai

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "TrendsRepository"

class TrendsRepository {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Returns today's top trending search topics for [countryCode] (ISO 3166-1 alpha-2, e.g. "DE").
     * Uses the unofficial Google Trends dailytrends endpoint — no API key required.
     * Returns an empty list on any failure so callers can proceed without trend data.
     */
    suspend fun fetchTrending(countryCode: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val url = "https://trends.google.com/trends/api/dailytrends" +
                "?hl=en&geo=${countryCode.uppercase()}&ns=15"
            val body = http.newCall(Request.Builder().url(url).build()).await()
                .body?.string() ?: return@withContext emptyList()

            // Response is XSSI-guarded with a ")]}'\n" prefix
            val json = body.removePrefix(")]}'\n")

            val root = JsonParser.parseString(json).asJsonObject
            val days = root
                .getAsJsonObject("default")
                ?.getAsJsonArray("trendingSearchesDays")
                ?: return@withContext emptyList()

            val results = mutableListOf<String>()
            days.firstOrNull()?.asJsonObject
                ?.getAsJsonArray("trendingSearches")
                ?.forEach { elem ->
                    elem.asJsonObject
                        .getAsJsonObject("title")
                        ?.get("query")?.asString
                        ?.let { results.add(it) }
                }
            Log.d(TAG, "Fetched ${results.size} trending topics for $countryCode")
            results.take(15)
        } catch (e: Exception) {
            Log.w(TAG, "Trends fetch failed (non-fatal): ${e.message}")
            emptyList()
        }
    }
}
