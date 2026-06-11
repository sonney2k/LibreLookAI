package com.librelookai.gemini

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.librelookai.billing.CreditPacks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Reads `config/publicPricing` from Firestore (post-multiplier coin prices) so
 * the client can render accurate cost badges without ever seeing the markup.
 *
 * Fallback order:
 *  1. Latest Firestore snapshot held in [costsState].
 *  2. Last-known costs persisted to SharedPreferences (offline first launch
 *     after the user has been online once).
 *  3. The hard-coded constants in [CreditPacks] as a last resort.
 *
 * Lifecycle: [start] attaches a snapshot listener; safe to call multiple
 * times — only the first one wires the listener. [stop] is provided for
 * symmetry but is not normally needed because the cache lives for the
 * process lifetime.
 */
object PricingClient {
    private const val TAG = "PricingClient"
    private const val PREFS = "librelookai_pricing"
    private const val KEY_COSTS_JSON = "public_costs_json"
    private const val KEY_PER_ITEM_COSTS_JSON = "public_per_item_costs_json"

    private val _costsState = MutableStateFlow<Map<String, Int>>(emptyMap())
    val costsState: StateFlow<Map<String, Int>> = _costsState.asStateFlow()

    private val _perItemCostsState = MutableStateFlow<Map<String, Int>>(emptyMap())
    val perItemCostsState: StateFlow<Map<String, Int>> = _perItemCostsState.asStateFlow()

    private var listenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null

    /** Idempotent. Loads cached costs immediately, then attaches the Firestore listener. */
    fun start(context: Context) {
        // Load any persisted snapshot first so callers get a non-empty map immediately.
        if (_costsState.value.isEmpty()) {
            _costsState.value = loadPersisted(context, KEY_COSTS_JSON)
        }
        if (_perItemCostsState.value.isEmpty()) {
            _perItemCostsState.value = loadPersisted(context, KEY_PER_ITEM_COSTS_JSON)
        }
        if (listenerRegistration != null) return
        if (FirebaseApp.getApps(context).isEmpty()) {
            Log.d(TAG, "Firebase not initialised — staying with defaults")
            return
        }
        listenerRegistration = FirebaseFirestore.getInstance()
            .collection("config").document("publicPricing")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "publicPricing listener error", err)
                    return@addSnapshotListener
                }
                snap ?: return@addSnapshotListener
                @Suppress("UNCHECKED_CAST")
                val costsRaw = snap.get("costs") as? Map<String, Any?>
                if (costsRaw != null) {
                    val parsed = costsRaw.mapNotNull { (k, v) ->
                        val n = (v as? Number)?.toInt() ?: return@mapNotNull null
                        k to n
                    }.toMap()
                    if (parsed.isNotEmpty()) {
                        _costsState.value = parsed
                        persist(context, KEY_COSTS_JSON, parsed)
                    }
                }
                @Suppress("UNCHECKED_CAST")
                val perItemRaw = snap.get("perItemCosts") as? Map<String, Any?>
                // Always overwrite (including with empty) so removing a per-item entry
                // server-side propagates — but persist only non-empty maps.
                val parsedPerItem = (perItemRaw ?: emptyMap()).mapNotNull { (k, v) ->
                    val n = (v as? Number)?.toInt() ?: return@mapNotNull null
                    k to n
                }.toMap()
                _perItemCostsState.value = parsedPerItem
                if (parsedPerItem.isNotEmpty()) persist(context, KEY_PER_ITEM_COSTS_JSON, parsedPerItem)
            }
    }

    fun stop() {
        listenerRegistration?.remove()
        listenerRegistration = null
    }

    /**
     * Coin cost for one invocation of [action]. Returns the server-published
     * price when available, falling back to the on-device defaults so the UI
     * never shows nothing.
     */
    fun coinCost(action: GeminiActionId): Int {
        val live = _costsState.value[action.key]
        if (live != null) return live
        return action.fallbackCost
    }

    /** Per-extra-item coin cost (0 = action doesn't scale with batch size). */
    fun perItemCost(action: GeminiActionId): Int {
        val live = _perItemCostsState.value[action.key]
        if (live != null) return live
        return action.fallbackPerItemCost
    }

    /**
     * Total coin cost for an action that returns [items] items in one call.
     * Mirrors the server formula: `base + perItem * (items - 1)`. Single-item
     * calls match [coinCost] exactly.
     */
    fun coinCostForItems(action: GeminiActionId, items: Int): Int {
        val n = items.coerceAtLeast(1)
        return coinCost(action) + perItemCost(action) * (n - 1)
    }

    /** Sum of [coinCost] for the given actions. */
    fun bulkCost(actions: List<GeminiActionId>): Int = actions.sumOf { coinCost(it) }

    private fun loadPersisted(context: Context, key: String): Map<String, Int> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            buildMap {
                obj.keys().forEach { k -> put(k, obj.getInt(k)) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse persisted costs ($key)", e)
            emptyMap()
        }
    }

    private fun persist(context: Context, key: String, costs: Map<String, Int>) {
        val obj = JSONObject()
        costs.forEach { (k, v) -> obj.put(k, v) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, obj.toString()).apply()
    }
}

/**
 * Mirror of the server-side action keys. The [key] must match what
 * `GeminiRepository` sends in the `X-AI-Action` header (and what the Cloud
 * Function reads from `config/pricing.costs`).
 *
 * [fallbackCost] is the last-ditch coin cost shown when neither Firestore nor
 * SharedPreferences has a value — kept in sync with the server defaults in
 * `firebase/functions/src/pricing.ts` (DEFAULT_PRICING × multiplier).
 */
/**
 * Mirror of the server-side action keys.
 *
 * [model] is the Gemini model the action runs against. The BYOK $ badge uses
 * it to pick per-token rates from [ModelPricingClient]. Keep in sync with the
 * constants in [GeminiRepository].
 */
enum class GeminiActionId(
    val key: String,
    val fallbackCost: Int,
    val fallbackPerItemCost: Int = 0,
    val model: String,
) {
    REMOVE_BACKGROUND("removeBackground", CreditPacks.COST_BG_REMOVAL, model = "gemini-3.1-flash-image-preview"),
    CLASSIFY_CLOTHING("classifyClothing", CreditPacks.COST_CLASSIFY, model = "gemini-3-flash-preview"),
    GENERATE_TEXT("generateText", CreditPacks.COST_TEXT, model = "gemini-3-flash-preview"),
    SEARCH_TRENDS("searchFashionTrends", CreditPacks.COST_TRENDS, model = "gemini-3-flash-preview"),
    TRY_ON_OUTFIT("tryOnOutfit", CreditPacks.COST_TRY_ON, model = "gemini-3.1-flash-image-preview"),
    OUTFIT_SUGGESTION("outfitSuggestion", CreditPacks.COST_OUTFIT_SUGGESTION, model = "gemini-3-flash-preview"),
}
