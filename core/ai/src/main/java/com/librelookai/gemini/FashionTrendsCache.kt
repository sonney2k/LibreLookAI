package com.librelookai.gemini

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.librelookai.data.drive.DriveService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One cached trend entry plus the wall-clock time it was fetched (epoch millis). */
private data class CachedTrends(
    val trends: FashionTrends,
    @SerializedName("fetched_at") val fetchedAt: Long = 0L,
)

/**
 * Region-specific, weekly-refreshed cache around [GeminiRepository.searchFashionTrends].
 *
 * Trend lookups are one of the more expensive Gemini calls (Google-Search grounding) yet the
 * answer barely changes day-to-day, so we amortise it: a fresh entry (< [TTL_MILLIS] old) is
 * served from a local disk file first, then from a shared Drive file (so a reinstall / second
 * device reuses what another already paid for), and only a genuine miss / stale entry triggers
 * the Gemini call — after which the result is written back to both caches.
 *
 * Cache key is the normalised [region] string, so each location keeps its own trends. On a Gemini
 * failure we fall back to a stale cached entry when one exists (better than no trends at all).
 */
@Singleton
class FashionTrendsCache @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val drive: DriveService,
    private val gemini: GeminiRepository,
) {
    private val gson = Gson()
    private val localFile: File get() = File(context.filesDir, LOCAL_FILE_NAME)

    /**
     * Returns trends for [region], serving a fresh cached copy when available and otherwise
     * fetching from Gemini and persisting the result. Returns null only when there is no cache
     * and the Gemini call fails / is unconfigured — including credits exhaustion (refactor § 6):
     * a 402 raises the global top-up dialog but degrades to stale/null here, and the caller's
     * main generateText call is what returns the typed [AiResult.InsufficientCredits].
     */
    suspend fun get(
        region: String,
        category: UsageCategory = UsageCategory.TRENDS,
    ): FashionTrends? = withContext(Dispatchers.IO) {
        val key = region.trim().lowercase(Locale.ROOT)
        if (key.isEmpty()) return@withContext gemini.searchFashionTrends(region, category)

        val now = System.currentTimeMillis()

        // 1) Local disk cache.
        val local = readLocal()
        local[key]?.let { if (now - it.fetchedAt < TTL_MILLIS) return@withContext it.trends }

        // 2) Shared Drive cache (only touched on a local miss / stale).
        val rootId = runCatching { drive.getOrCreateFolder() }.getOrNull()
        val remote = if (rootId != null) readRemote(rootId) else emptyMap()
        remote[key]?.let {
            if (now - it.fetchedAt < TTL_MILLIS) {
                writeLocal(local + (key to it))   // backfill local from the shared copy
                return@withContext it.trends
            }
        }

        // 3) Genuine miss / stale → fetch fresh and persist to both caches.
        val fresh = gemini.searchFashionTrends(region, category)
        if (fresh != null) {
            val entry = CachedTrends(fresh, now)
            writeLocal(local + (key to entry))
            if (rootId != null) {
                runCatching { drive.saveTrendsCacheJson(rootId, gson.toJson(remote + (key to entry))) }
                    .onFailure { Log.w(TAG, "saveTrendsCacheJson failed (non-fatal): ${it.message}") }
            }
            return@withContext fresh
        }

        // 4) Fetch failed → degrade to a stale entry if we have one.
        (local[key] ?: remote[key])?.trends
    }

    private fun readLocal(): Map<String, CachedTrends> =
        runCatching {
            if (!localFile.exists()) emptyMap()
            else gson.fromJson<Map<String, CachedTrends>>(localFile.readText(), MAP_TYPE) ?: emptyMap()
        }.getOrDefault(emptyMap())

    private fun writeLocal(map: Map<String, CachedTrends>) {
        runCatching { localFile.writeText(gson.toJson(map)) }
            .onFailure { Log.w(TAG, "writeLocal failed (non-fatal): ${it.message}") }
    }

    private suspend fun readRemote(rootId: String): Map<String, CachedTrends> =
        runCatching {
            drive.loadTrendsCacheJson(rootId)
                ?.let { gson.fromJson<Map<String, CachedTrends>>(it, MAP_TYPE) }
                ?: emptyMap()
        }.getOrDefault(emptyMap())

    companion object {
        private const val TAG = "FashionTrendsCache"
        private const val LOCAL_FILE_NAME = "trends_cache.json"
        /** Refresh interval: one week. */
        private const val TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
        private val MAP_TYPE = object : TypeToken<Map<String, CachedTrends>>() {}.type
    }
}
