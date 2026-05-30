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
 * Reads `config/modelPricing` so the client can render BYOK € badges and
 * snapshot per-event EUR on each Gemini call without hard-coding Google's
 * list prices in the APK.
 *
 * Rates are **EUR-native**: Google bills the EU billing account directly in
 * EUR, so there is no USD ground truth and no FX layer. The schema carries a
 * per-token-type split (text/image/cached input, text/image output) matching
 * the bill — see `plan/OPTIONS.md` "Option B". Today text-in and image-in are
 * the same rate, so input is billed at the text-in rate; output is billed at
 * the image-out rate for image-generation models ([isImageOutputModel]) and at
 * the text-out rate otherwise.
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

    /** Per-token EUR rates for [model] (Firestore values, or fallback). */
    fun ratesFor(model: String): ModelTokenRates = _snapshotState.value.ratesFor(model)

    /**
     * True when a model emits image tokens as its primary output (Gemini's
     * "*-image-*" generation models), so output should bill at the image-out
     * rate. Cheap name heuristic — the only divergence between text-out and
     * image-out rates is on these models.
     */
    fun isImageOutputModel(model: String): Boolean = model.contains("image", ignoreCase = true)

    private fun parseSnapshot(snap: com.google.firebase.firestore.DocumentSnapshot): ModelPricingSnapshot? {
        @Suppress("UNCHECKED_CAST")
        val modelsRaw = snap.get("models") as? Map<String, Any?> ?: return null
        @Suppress("UNCHECKED_CAST")
        val fallbackRaw = snap.get("fallback") as? Map<String, Any?>
        val models = modelsRaw.mapNotNull { (k, v) ->
            val m = v as? Map<*, *> ?: return@mapNotNull null
            (ratesFromPerM(m) ?: return@mapNotNull null).let { k to it }
        }.toMap()
        val fallback = fallbackRaw?.let { ratesFromPerM(it) } ?: DefaultModelPricing.snapshot.fallback
        if (models.isEmpty()) return null
        return ModelPricingSnapshot(models, fallback)
    }

    /** Build per-token rates from a `*PerM` (per-million) Firestore/JSON map. */
    private fun ratesFromPerM(m: Map<*, *>): ModelTokenRates? {
        val textIn = (m["textInPerM"] as? Number)?.toDouble() ?: return null
        val textOut = (m["textOutPerM"] as? Number)?.toDouble() ?: return null
        val imageIn = (m["imageInPerM"] as? Number)?.toDouble() ?: textIn
        val cachedTextIn = (m["cachedTextInPerM"] as? Number)?.toDouble() ?: 0.0
        val imageOut = (m["imageOutPerM"] as? Number)?.toDouble() ?: textOut
        return ModelTokenRates(
            textInPerToken = textIn / 1_000_000.0,
            imageInPerToken = imageIn / 1_000_000.0,
            cachedTextInPerToken = cachedTextIn / 1_000_000.0,
            textOutPerToken = textOut / 1_000_000.0,
            imageOutPerToken = imageOut / 1_000_000.0,
        )
    }

    private fun loadPersisted(context: Context): ModelPricingSnapshot? {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null) ?: return null
        return try {
            val obj = JSONObject(json)
            val fallback = obj.optJSONObject("fallback")?.let { ratesFromJson(it) }
                ?: DefaultModelPricing.snapshot.fallback
            val modelsObj = obj.getJSONObject("models")
            val models = buildMap {
                modelsObj.keys().forEach { k -> put(k, ratesFromJson(modelsObj.getJSONObject(k))) }
            }
            ModelPricingSnapshot(models, fallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse persisted model pricing", e)
            null
        }
    }

    /** Read per-token rates from our own persisted JSON shape. */
    private fun ratesFromJson(o: JSONObject): ModelTokenRates = ModelTokenRates(
        textInPerToken = o.getDouble("textInPerToken"),
        imageInPerToken = o.optDouble("imageInPerToken", o.getDouble("textInPerToken")),
        cachedTextInPerToken = o.optDouble("cachedTextInPerToken", 0.0),
        textOutPerToken = o.getDouble("textOutPerToken"),
        imageOutPerToken = o.optDouble("imageOutPerToken", o.getDouble("textOutPerToken")),
    )

    private fun persist(context: Context, snapshot: ModelPricingSnapshot) {
        val obj = JSONObject()
        obj.put("fallback", snapshot.fallback.toJson())
        val models = JSONObject()
        snapshot.models.forEach { (k, v) -> models.put(k, v.toJson()) }
        obj.put("models", models)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_JSON, obj.toString()).apply()
    }

    private fun ModelTokenRates.toJson(): JSONObject = JSONObject().apply {
        put("textInPerToken", textInPerToken)
        put("imageInPerToken", imageInPerToken)
        put("cachedTextInPerToken", cachedTextInPerToken)
        put("textOutPerToken", textOutPerToken)
        put("imageOutPerToken", imageOutPerToken)
    }
}

/**
 * Per-token EUR rates for one model (already divided by 1M). Input and output
 * are split by token type to match Google's bill; [eurFor] applies the rate
 * that fits a given call.
 */
data class ModelTokenRates(
    val textInPerToken: Double,
    val imageInPerToken: Double,
    val cachedTextInPerToken: Double,
    val textOutPerToken: Double,
    val imageOutPerToken: Double,
) {
    /**
     * EUR cost of one call. Input bills at the text-in rate (text-in and
     * image-in are the same today); output bills at the image-out rate when
     * [outputIsImage], else the text-out rate.
     */
    fun eurFor(inputTokens: Int, outputTokens: Int, outputIsImage: Boolean): Double =
        inputTokens * textInPerToken +
            outputTokens * (if (outputIsImage) imageOutPerToken else textOutPerToken)
}

data class ModelPricingSnapshot(
    val models: Map<String, ModelTokenRates>,
    val fallback: ModelTokenRates,
) {
    fun ratesFor(model: String): ModelTokenRates = models[model] ?: fallback
}

/**
 * Hard-coded fallback used when Firestore is unreachable and no persisted
 * snapshot is available. EUR-native; kept in sync with `seed.ts` on the server.
 */
object DefaultModelPricing {
    private fun perM(
        textIn: Double, textOut: Double, imageOut: Double = textOut,
    ) = ModelTokenRates(
        textInPerToken = textIn / 1_000_000.0,
        imageInPerToken = textIn / 1_000_000.0,
        cachedTextInPerToken = 0.0428 / 1_000_000.0,
        textOutPerToken = textOut / 1_000_000.0,
        imageOutPerToken = imageOut / 1_000_000.0,
    )

    val snapshot = ModelPricingSnapshot(
        models = mapOf(
            "gemini-3-flash-preview" to perM(textIn = 0.4275, textOut = 2.5651),
            "gemini-3.1-flash-image-preview" to perM(textIn = 0.4275, textOut = 2.5651, imageOut = 51.303),
        ),
        fallback = perM(textIn = 0.4275, textOut = 2.5651),
    )
}
