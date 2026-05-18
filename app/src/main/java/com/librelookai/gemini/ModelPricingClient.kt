package com.librelookai.gemini

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Reads `config/modelPricing` so the client can render BYOK $ badges and
 * snapshot per-event USD on each Gemini call without hard-coding Google's
 * list prices in the APK.
 *
 * Fallback order (same pattern as [PricingClient]):
 *  1. Latest Firestore snapshot held in [snapshotState].
 *  2. SharedPreferences cache of the last good snapshot.
 *  3. The hard-coded defaults in [DefaultModelPricing] — kept in sync with the
 *     `seed.ts` defaults on the server side.
 *
 * Lifecycle: [start] is idempotent — call once at app start. The listener
 * lives for the process lifetime.
 */
object ModelPricingClient {
    private const val TAG = "ModelPricingClient"
    private const val PREFS = "librelookai_model_pricing"
    private const val KEY_JSON = "model_pricing_json"

    private val _snapshotState = MutableStateFlow(DefaultModelPricing.snapshot)
    val snapshotState: StateFlow<ModelPricingSnapshot> = _snapshotState.asStateFlow()

    private var listenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null

    fun start(context: Context) {
        // Load any persisted snapshot first so callers get rates immediately.
        loadPersisted(context)?.let { _snapshotState.value = it }

        if (listenerRegistration != null) return
        if (FirebaseApp.getApps(context).isEmpty()) {
            Log.d(TAG, "Firebase not initialised — staying with defaults")
            return
        }
        listenerRegistration = FirebaseFirestore.getInstance()
            .collection("config").document("modelPricing")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "modelPricing listener error", err)
                    return@addSnapshotListener
                }
                snap ?: return@addSnapshotListener
                val parsed = parseSnapshot(snap) ?: return@addSnapshotListener
                _snapshotState.value = parsed
                persist(context, parsed)
            }
    }

    fun stop() {
        listenerRegistration?.remove()
        listenerRegistration = null
    }

    /** Per-token USD rates for [model] (Firestore values, or fallback). */
    fun ratesFor(model: String): TokenRates = _snapshotState.value.ratesFor(model)

    /** USD→EUR conversion factor used purely for display. */
    fun usdToEur(): Double = _snapshotState.value.fxUsdToEur

    private fun parseSnapshot(snap: com.google.firebase.firestore.DocumentSnapshot): ModelPricingSnapshot? {
        @Suppress("UNCHECKED_CAST")
        val modelsRaw = snap.get("models") as? Map<String, Any?> ?: return null
        @Suppress("UNCHECKED_CAST")
        val fallbackRaw = snap.get("fallback") as? Map<String, Any?>
        val fx = (snap.get("fxUsdToEur") as? Number)?.toDouble() ?: DefaultModelPricing.snapshot.fxUsdToEur
        val models = modelsRaw.mapNotNull { (k, v) ->
            val m = v as? Map<*, *> ?: return@mapNotNull null
            val inP = (m["inUsdPerM"] as? Number)?.toDouble() ?: return@mapNotNull null
            val outP = (m["outUsdPerM"] as? Number)?.toDouble() ?: return@mapNotNull null
            k to TokenRates(inP / 1_000_000.0, outP / 1_000_000.0)
        }.toMap()
        val fallback = fallbackRaw?.let { m ->
            val inP = (m["inUsdPerM"] as? Number)?.toDouble()
            val outP = (m["outUsdPerM"] as? Number)?.toDouble()
            if (inP != null && outP != null) TokenRates(inP / 1_000_000.0, outP / 1_000_000.0)
            else null
        } ?: DefaultModelPricing.snapshot.fallback
        if (models.isEmpty()) return null
        return ModelPricingSnapshot(models, fallback, fx)
    }

    private fun loadPersisted(context: Context): ModelPricingSnapshot? {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null) ?: return null
        return try {
            val obj = JSONObject(json)
            val fx = obj.optDouble("fxUsdToEur", DefaultModelPricing.snapshot.fxUsdToEur)
            val fallbackObj = obj.optJSONObject("fallback")
            val fallback = if (fallbackObj != null) TokenRates(
                inputPerToken = fallbackObj.getDouble("inputPerToken"),
                outputPerToken = fallbackObj.getDouble("outputPerToken"),
            ) else DefaultModelPricing.snapshot.fallback
            val modelsObj = obj.getJSONObject("models")
            val models = buildMap {
                modelsObj.keys().forEach { k ->
                    val m = modelsObj.getJSONObject(k)
                    put(k, TokenRates(m.getDouble("inputPerToken"), m.getDouble("outputPerToken")))
                }
            }
            ModelPricingSnapshot(models, fallback, fx)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse persisted model pricing", e)
            null
        }
    }

    private fun persist(context: Context, snapshot: ModelPricingSnapshot) {
        val obj = JSONObject()
        obj.put("fxUsdToEur", snapshot.fxUsdToEur)
        obj.put("fallback", JSONObject().apply {
            put("inputPerToken", snapshot.fallback.inputPerToken)
            put("outputPerToken", snapshot.fallback.outputPerToken)
        })
        val models = JSONObject()
        snapshot.models.forEach { (k, v) ->
            models.put(k, JSONObject().apply {
                put("inputPerToken", v.inputPerToken)
                put("outputPerToken", v.outputPerToken)
            })
        }
        obj.put("models", models)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_JSON, obj.toString()).apply()
    }
}

/** Per-token USD rates (already divided by 1M). */
data class TokenRates(val inputPerToken: Double, val outputPerToken: Double) {
    fun usdFor(inputTokens: Int, outputTokens: Int): Double =
        inputTokens * inputPerToken + outputTokens * outputPerToken
}

data class ModelPricingSnapshot(
    val models: Map<String, TokenRates>,
    val fallback: TokenRates,
    val fxUsdToEur: Double,
) {
    fun ratesFor(model: String): TokenRates = models[model] ?: fallback
}

/**
 * Hard-coded fallback used when Firestore is unreachable and no persisted
 * snapshot is available. Kept in sync with `seed.ts` on the server.
 */
object DefaultModelPricing {
    val snapshot = ModelPricingSnapshot(
        models = mapOf(
            "gemini-3-flash-preview" to TokenRates(0.30 / 1_000_000.0, 2.50 / 1_000_000.0),
            "gemini-3.1-flash-image-preview" to TokenRates(0.30 / 1_000_000.0, 30.00 / 1_000_000.0),
        ),
        fallback = TokenRates(0.30 / 1_000_000.0, 2.50 / 1_000_000.0),
        fxUsdToEur = 0.92,
    )
}
