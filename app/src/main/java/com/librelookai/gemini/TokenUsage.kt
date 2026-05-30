package com.librelookai.gemini
import android.app.Application
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.loadTokenUsageJsonl
import com.librelookai.data.drive.saveTokenUsageJsonl
import com.librelookai.MainActivity

/** Coarse buckets for "where did the tokens go" — surfaced in the Settings → Credits chart. */
enum class UsageCategory(val storageKey: String) {
    BG_REMOVAL("bg_removal"),
    TAGGING("tagging"),
    TRY_ON("try_on"),
    OUTFIT_PREDICT("outfit_predict"),
    OUTFIT_COMPOSE("outfit_compose"),
    GAP_ANALYSIS("gap_analysis"),
    REPLACEMENTS("replacements"),
    TRENDS("trends"),
    TRAVEL("travel"),
    OTHER("other");

    companion object {
        fun fromKey(key: String?): UsageCategory =
            values().firstOrNull { it.storageKey == key } ?: OTHER
    }
}

/**
 * One Gemini call. `inputTokens` + `outputTokens` come from the response's `usageMetadata`;
 * either may be 0 if Gemini omits the count.
 *
 * [eurAtRecord] is the EUR cost computed using the rates valid at record time. Snapshotting
 * the value means that aggregations stay correct after Google changes their pricing — old
 * events keep their historical price. Nullable for backwards compatibility: events written
 * before the EUR switch carry a legacy `"usd"` key (not read here), so they fall back to the
 * current EUR rates in [GeminiPricing.eurFor].
 */
data class UsageEvent(
    @SerializedName("ts") val timestampMs: Long,
    @SerializedName("cat") val categoryKey: String,
    @SerializedName("model") val model: String,
    @SerializedName("in") val inputTokens: Int,
    @SerializedName("out") val outputTokens: Int,
    @SerializedName("eur") val eurAtRecord: Double? = null,
) {
    val totalTokens: Int get() = inputTokens + outputTokens
    val category: UsageCategory get() = UsageCategory.fromKey(categoryKey)
}

/**
 * EUR price helper. Rates come from [ModelPricingClient] (live Firestore snapshot, persisted
 * to SharedPreferences, with hard-coded fallbacks in [DefaultModelPricing]). All figures are
 * EUR-native — Google bills the EU account directly in EUR, so there is no FX conversion.
 */
object GeminiPricing {
    fun eurFor(model: String, inputTokens: Int, outputTokens: Int): Double =
        ModelPricingClient.ratesFor(model)
            .eurFor(inputTokens, outputTokens, ModelPricingClient.isImageOutputModel(model))

    /** Prefer the per-event snapshot; fall back to current rates when missing. */
    fun eurFor(event: UsageEvent): Double =
        event.eurAtRecord ?: eurFor(event.model, event.inputTokens, event.outputTokens)
}

/**
 * Singleton store of Gemini token usage. Append-only JSONL at `filesDir/usage/usage.jsonl`,
 * mirrored to Drive at `LibreLookAI/_token_usage.jsonl` when [syncWithDrive] is invoked.
 *
 * The store is a simple in-memory list backed by a flow — large enough for years of normal
 * personal use (a power user generates a few hundred events per month, a few KB).
 */
class TokenUsageRepository private constructor(private val app: Application) {

    companion object {
        private const val TAG = "TokenUsage"
        private const val FILE_NAME = "usage.jsonl"
        const val DRIVE_FILE_NAME = "_token_usage.jsonl"
        private const val DRIVE_FLUSH_DEBOUNCE_MS = 30_000L

        @Volatile private var INSTANCE: TokenUsageRepository? = null
        fun get(app: Application): TokenUsageRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TokenUsageRepository(app).also {
                    INSTANCE = it
                    it.loadFromDisk()
                }
            }
    }

    private val gson = Gson()
    private val dir = File(app.filesDir, "usage").also { it.mkdirs() }
    private val file = File(dir, FILE_NAME)
    private val ioMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableStateFlow<List<UsageEvent>>(emptyList())
    val events: StateFlow<List<UsageEvent>> = _events.asStateFlow()

    private var dirtySinceLastDriveFlush = false
    private var pendingFlush: Job? = null

    private fun loadFromDisk() {
        if (!file.exists()) return
        val parsed = file.readLines().mapNotNull { line ->
            if (line.isBlank()) null
            else try { gson.fromJson(line, UsageEvent::class.java) }
            catch (e: JsonSyntaxException) { null }
        }
        _events.value = parsed
        Log.d(TAG, "Loaded ${parsed.size} usage events from disk")
    }

    /** Records one event, persists locally, schedules a debounced Drive flush. */
    fun record(category: UsageCategory, model: String, inputTokens: Int, outputTokens: Int) {
        val inT = inputTokens.coerceAtLeast(0)
        val outT = outputTokens.coerceAtLeast(0)
        val ev = UsageEvent(
            timestampMs = System.currentTimeMillis(),
            categoryKey = category.storageKey,
            model = model,
            inputTokens = inT,
            outputTokens = outT,
            // Snapshot € at record time so historical aggregation isn't disrupted by
            // later price changes. Aggregator prefers this value when present.
            eurAtRecord = GeminiPricing.eurFor(model, inT, outT),
        )
        scope.launch {
            ioMutex.withLock {
                file.appendText(gson.toJson(ev) + "\n")
                _events.value = _events.value + ev
                dirtySinceLastDriveFlush = true
            }
            scheduleDriveFlushIfPossible()
        }
    }

    /** Replaces the entire log (used after pulling from Drive on first sync). */
    private suspend fun replaceAll(newEvents: List<UsageEvent>) = ioMutex.withLock {
        val sorted = newEvents.sortedBy { it.timestampMs }
        file.writeText(sorted.joinToString("\n") { gson.toJson(it) } + if (sorted.isEmpty()) "" else "\n")
        _events.value = sorted
    }

    /**
     * Pulls remote usage from Drive, merges with local, writes union back. Idempotent: safe to
     * call repeatedly. Called on app start/resume by MainActivity once Drive auth is ready.
     */
    suspend fun syncWithDrive(drive: DriveRepository) = withContext(Dispatchers.IO) {
        try {
            val rootFolderId = drive.getOrCreateFolder()
            val remote = drive.loadTokenUsageJsonl(rootFolderId)
            val remoteEvents = remote
                ?.lineSequence()
                ?.mapNotNull { line ->
                    if (line.isBlank()) null
                    else try { gson.fromJson(line, UsageEvent::class.java) }
                    catch (e: JsonSyntaxException) { null }
                }
                ?.toList()
                ?: emptyList()
            val local = _events.value
            val merged = mergeUnique(local + remoteEvents)
            if (merged.size != local.size || merged.size != remoteEvents.size) {
                replaceAll(merged)
                drive.saveTokenUsageJsonl(rootFolderId, merged.joinToString("\n") { gson.toJson(it) })
                dirtySinceLastDriveFlush = false
                Log.d(TAG, "Drive sync: ${remoteEvents.size} remote, ${local.size} local → ${merged.size} merged")
            } else {
                Log.d(TAG, "Drive sync: in-sync (${local.size} events)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Drive sync failed (non-fatal): ${e.message}")
        }
    }

    /** Forces an immediate Drive write of the current local log. */
    suspend fun flushToDrive(drive: DriveRepository) = withContext(Dispatchers.IO) {
        if (!dirtySinceLastDriveFlush) return@withContext
        try {
            val rootFolderId = drive.getOrCreateFolder()
            val payload = ioMutex.withLock {
                _events.value.joinToString("\n") { gson.toJson(it) }
            }
            drive.saveTokenUsageJsonl(rootFolderId, payload)
            dirtySinceLastDriveFlush = false
            Log.d(TAG, "Drive flushed (${_events.value.size} events)")
        } catch (e: Exception) {
            Log.w(TAG, "Drive flush failed (non-fatal): ${e.message}")
        }
    }

    private fun scheduleDriveFlushIfPossible() {
        // Drive flush is performed by MainActivity via [flushToDrive] on lifecycle pause. We just
        // arm the dirty flag; the activity sees it on its next pass. Kept as a hook for a future
        // background flush worker.
        if (pendingFlush?.isActive == true) return
        pendingFlush = scope.launch {
            delay(TimeUnit.MILLISECONDS.toMillis(DRIVE_FLUSH_DEBOUNCE_MS))
        }
    }

    private fun mergeUnique(all: List<UsageEvent>): List<UsageEvent> {
        // De-dupe by (ts + category + model + tokens) — tokens differ even for same-instant events
        // from different categories, so collisions are vanishingly rare.
        val seen = HashSet<String>(all.size)
        val out = ArrayList<UsageEvent>(all.size)
        for (e in all.sortedBy { it.timestampMs }) {
            val key = "${e.timestampMs}|${e.categoryKey}|${e.model}|${e.inputTokens}|${e.outputTokens}"
            if (seen.add(key)) out += e
        }
        return out
    }
}

// ---------- Aggregation helpers (used by UsageViewModel + UI) ----------

data class UsageWindowTotals(
    val inputTokens: Int,
    val outputTokens: Int,
    val eur: Double,
    val perCategory: Map<UsageCategory, CategoryTotals>,
) {
    val tokens: Int get() = inputTokens + outputTokens
}

data class CategoryTotals(
    val inputTokens: Int,
    val outputTokens: Int,
    val eur: Double,
    val calls: Int,
) {
    val tokens: Int get() = inputTokens + outputTokens
}

object UsageAggregator {
    fun totals(events: List<UsageEvent>, sinceMs: Long? = null): UsageWindowTotals {
        val window = if (sinceMs == null) events else events.filter { it.timestampMs >= sinceMs }
        var inTokens = 0
        var outTokens = 0
        var eur = 0.0
        val per = HashMap<UsageCategory, MutableList<UsageEvent>>()
        for (e in window) {
            inTokens += e.inputTokens
            outTokens += e.outputTokens
            eur += GeminiPricing.eurFor(e)
            per.getOrPut(e.category) { mutableListOf() } += e
        }
        val perCat = per.mapValues { (_, list) ->
            CategoryTotals(
                inputTokens = list.sumOf { it.inputTokens },
                outputTokens = list.sumOf { it.outputTokens },
                eur = list.sumOf { GeminiPricing.eurFor(it) },
                calls = list.size,
            )
        }
        return UsageWindowTotals(inTokens, outTokens, eur, perCat)
    }

    /**
     * Tokens per local-calendar day for the last [days] days, oldest first. The last entry
     * always corresponds to "today" (midnight-of-today through now).
     */
    fun dailyTokens(events: List<UsageEvent>, days: Int): List<Pair<Long, Int>> {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis
        val dayMs = TimeUnit.DAYS.toMillis(1)
        val buckets = IntArray(days)
        val windowStart = todayStart - (days - 1) * dayMs
        for (e in events) {
            if (e.timestampMs < windowStart) continue
            val idx = ((e.timestampMs - windowStart) / dayMs).toInt()
            if (idx in 0 until days) buckets[idx] += e.totalTokens
        }
        return (0 until days).map { i -> (windowStart + i * dayMs) to buckets[i] }
    }
}
