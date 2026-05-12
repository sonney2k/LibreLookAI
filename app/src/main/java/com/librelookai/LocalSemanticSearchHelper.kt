package com.librelookai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.components.containers.Embedding
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * On-device semantic text search over wardrobe items, powered by MediaPipe's TextEmbedder
 * (English Universal Sentence Encoder) plus ML Kit Translate for non-English inputs.
 *
 * Pipeline:
 *  1. If the active source language is not English, translate the query and each item's tag
 *     document into English via ML Kit (model downloaded lazily, cached on-device).
 *  2. Embed the English strings with `universal_sentence_encoder.tflite` (must live at
 *     `app/src/main/assets/`).
 *  3. Rank by cosine similarity > [DEFAULT_THRESHOLD], sorted descending.
 *
 * Embeddings and translations are cached in-memory keyed by `driveId:version:srcLang` so
 * the second search in a session does no extra translation or embedding work.
 */
class LocalSemanticSearchHelper(private val context: Context) {

    private val mutex = Mutex()
    private var embedder: TextEmbedder? = null
    private var initFailed = false

    /** Active source language (BCP-47), set by the host VM to mirror the app language. */
    @Volatile private var sourceLanguage: String = TranslateLanguage.ENGLISH

    private val translatorMutex = Mutex()
    private var translator: Translator? = null
    private var translatorLang: String? = null
    private var translatorReady = false

    /** `key = "driveId:version:srcLang"` → translated English doc string. */
    private val translatedDocCache = HashMap<String, String>()
    /** `key = "driveId:version:srcLang"` → embedding of the English doc string. */
    private val itemCache = HashMap<String, FloatArray>()

    /** Update the source language used for translation. Safe to call from the main thread. */
    fun setSourceLanguage(bcp47: String) {
        sourceLanguage = bcp47.lowercase()
    }

    private suspend fun ensureEmbedder(): TextEmbedder? = mutex.withLock {
        if (embedder != null) return@withLock embedder
        if (initFailed) return@withLock null
        runCatching {
            TextEmbedder.createFromFile(context, MODEL_ASSET)
        }.onFailure {
            initFailed = true
            Log.w(TAG, "TextEmbedder unavailable (missing $MODEL_ASSET?): ${it.message}")
        }.getOrNull()?.also { embedder = it }
    }

    /** Returns a ready-to-use translator from [src] → English, or null when [src] is already
     *  English or model download fails. */
    private suspend fun ensureTranslator(src: String): Translator? {
        if (src == TranslateLanguage.ENGLISH) return null
        translatorMutex.withLock {
            if (translator != null && translatorLang == src && translatorReady) return translator
            translator?.close()
            translator = null
            translatorLang = null
            translatorReady = false
            val opts = TranslatorOptions.Builder()
                .setSourceLanguage(src)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
            val t = runCatching { Translation.getClient(opts) }.getOrNull() ?: return null
            val ok = runCatching {
                t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                true
            }.getOrElse {
                Log.w(TAG, "Translate model download failed ($src→en): ${it.message}")
                false
            }
            if (!ok) {
                runCatching { t.close() }
                return null
            }
            translator = t
            translatorLang = src
            translatorReady = true
            return t
        }
    }

    private suspend fun translateToEnglish(text: String, t: Translator?): String {
        if (t == null) return text
        return runCatching { t.translate(text).await() }.getOrElse {
            Log.w(TAG, "Translate failed, falling back to source text: ${it.message}")
            text
        }
    }

    suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.Default) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext null
        val e = ensureEmbedder() ?: return@withContext null
        runCatching {
            e.embed(trimmed).embeddingResult().embeddings().firstOrNull()?.toFloatArray()
        }.getOrNull()
    }

    /** Search [items] for entries semantically similar to [query]. Returns matches with
     *  cosine similarity > [threshold], sorted descending by score. */
    suspend fun searchWardrobe(
        query: String,
        items: List<DriveImage>,
        threshold: Float = DEFAULT_THRESHOLD,
    ): List<ScoredItem> = withContext(Dispatchers.Default) {
        val src = sourceLanguage
        val t = ensureTranslator(src)
        val qEnglish = translateToEnglish(query, t)
        val qTokens = tokenize(qEnglish)
        val qVec = embed(qEnglish)
        if (qTokens.isEmpty() && qVec == null) return@withContext emptyList()

        coroutineScope {
            items.map { item ->
                async {
                    val doc = documentString(item) ?: return@async null
                    val key = "${item.driveId}:${item.version}:$src"
                    val englishDoc = translatedDocCache[key]
                        ?: translateToEnglish(doc, t).also { translatedDocCache[key] = it }
                    val docTokens = tokenize(englishDoc)

                    val keyword = keywordOverlap(qTokens, docTokens)
                    val semantic = qVec?.let { qv ->
                        val vec = itemCache[key] ?: embed(englishDoc)?.also { itemCache[key] = it }
                        vec?.let { cosineSimilarity(qv, it) } ?: 0f
                    } ?: 0f

                    // Hybrid: items containing every query token literally always rank above
                    // those that don't. Within each tier we fall back to semantic similarity.
                    val score = when {
                        keyword >= 1f -> 1f + semantic                  // all query tokens present
                        keyword > 0f  -> 0.85f + 0.15f * keyword + 0.1f * semantic // partial keyword match
                        else          -> semantic                       // pure semantic
                    }
                    if (score > threshold) ScoredItem(item, score) else null
                }
            }.mapNotNull { it.await() }.sortedByDescending { it.score }
        }
    }

    /** Lowercased alphanumeric tokens. */
    private fun tokenize(text: String): List<String> =
        text.lowercase().split(TOKEN_SPLIT_REGEX).filter { it.isNotEmpty() }

    /** Fraction of query tokens that appear in the doc, by exact token match. */
    private fun keywordOverlap(qTokens: List<String>, docTokens: List<String>): Float {
        if (qTokens.isEmpty()) return 0f
        val docSet = docTokens.toHashSet()
        val hit = qTokens.count { it in docSet }
        return hit.toFloat() / qTokens.size
    }

    data class ScoredItem(val item: DriveImage, val score: Float)

    companion object {
        private const val TAG = "SemanticSearch"
        private const val MODEL_ASSET = "universal_sentence_encoder.tflite"
        const val DEFAULT_THRESHOLD = 0.65f
        private val TOKEN_SPLIT_REGEX = Regex("[^\\p{L}\\p{Nd}]+")

        /** Combine an item's tags into a single space-separated document string used for
         *  embedding. Returns null when the item has no tags worth indexing. */
        fun documentString(item: DriveImage): String? {
            val t = item.tags ?: return null
            val parts = buildList {
                if (t.label.isNotBlank()) add(t.label)
                if (t.type.isNotBlank()) add(t.type)
                if (t.category.isNotBlank()) add(t.category)
                addAll(t.uses)
                addAll(t.colors)
                addAll(t.seasonality)
                addAll(t.aesthetic)
                addAll(t.fit)
                addAll(t.material)
                addAll(t.pattern)
            }.filter { it.isNotBlank() }
            if (parts.isEmpty()) return null
            return parts.joinToString(" ")
        }
    }
}

private fun Embedding.toFloatArray(): FloatArray = floatEmbedding()

/** Cosine similarity in [-1, 1]. Returns 0 when either vector is zero-length. */
private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    val n = minOf(a.size, b.size)
    if (n == 0) return 0f
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in 0 until n) {
        val ai = a[i]
        val bi = b[i]
        dot += ai * bi
        na += ai * ai
        nb += bi * bi
    }
    val denom = sqrt(na) * sqrt(nb)
    if (denom == 0.0) return 0f
    return (dot / denom).toFloat()
}
