package com.librelookai.wardrobe

import android.app.Application
import android.content.Context
import com.librelookai.gemini.ClothingTags
import com.librelookai.ml.EmbeddingService
import java.io.File

internal val FUZZY_TOKEN_SPLIT = Regex("[^\\p{L}\\p{Nd}]+")

/** Resource-loading context whose locale follows the active app language, so `getString`
 *  returns the same localized tag values the UI shows. [geminiLanguage] is the Gemini-facing
 *  language name mirrored from UserPreferences ("English", "German", …). */
internal fun localizedSearchContext(app: Application, geminiLanguage: String): Context {
    val locale = if (geminiLanguage == "German") java.util.Locale("de") else java.util.Locale.ENGLISH
    val cfg = android.content.res.Configuration(app.resources.configuration)
    cfg.setLocale(locale)
    return app.createConfigurationContext(cfg)
}

/** Search wardrobe tags by literal substring match with a small Levenshtein tolerance for
 *  typos, scored per query token and sorted by descending match quality. */
internal fun fuzzyMatchByTags(query: String, items: List<DriveImage>, localeCtx: Context): List<FindByPhotoMatch> {
    val tokens = normalizeForSearch(query).split(FUZZY_TOKEN_SPLIT).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return emptyList()
    return items.mapNotNull { item ->
        val canonical = item.tags?.let { tagsToStrings(it) } ?: return@mapNotNull null
        // Stored tags are canonical English (e.g. "red", "white"); the UI displays them
        // through `tagValueResId` → localized string ("Rot", "Weiß"). Match against both
        // forms so users can search in either language.
        val tagStrings = canonical.flatMap { v ->
            val resId = tagValueResId(v)
            if (resId != null) listOf(v, localeCtx.getString(resId)) else listOf(v)
        }
        if (tagStrings.isEmpty()) return@mapNotNull null
        var totalScore = 0f
        var allMatched = true
        for (tok in tokens) {
            val best = bestTokenScore(tok, tagStrings)
            if (best <= 0f) { allMatched = false; break }
            totalScore += best
        }
        if (!allMatched) null else FindByPhotoMatch(item, totalScore / tokens.size)
    }.sortedByDescending { it.score }
}

/**
 * Run the on-device "find item by photo" similarity search over [pool] (the cross-closet
 * snapshot) and assemble the results-sheet state, including the debug artifacts the
 * similarity preview shows.
 */
internal suspend fun findByPhotoSearch(
    rawFile: File,
    pool: List<DriveImage>,
    cacheDir: File,
    debugOutputDir: File,
    topK: Int,
): FindByPhoto {
    EmbeddingService.syncIndex(pool, cacheDir)
    val sim = EmbeddingService.findSimilarWithDebug(
        file = rawFile,
        threshold = -1f,
        topK = topK,
        processedOutputDir = debugOutputDir,
    )
    val byId = pool.associateBy { it.driveId }
    val resolved = sim?.matches.orEmpty().mapNotNull { m ->
        byId[m.driveId]?.let { FindByPhotoMatch(it, m.score) }
    }
    return FindByPhoto(
        queryPath = rawFile.absolutePath,
        matches = resolved,
        isSearching = false,
        processedPath = sim?.processedPath,
        segmented = sim?.segmented ?: false,
        hist = sim?.hist,
        vec = sim?.vec,
        pHash = sim?.pHash,
    )
}

/**
 * Embed [rawFile] and score it against the index restricted to [candidates], returning the
 * top matches sorted by descending similarity. Used by the composer slot pickers for an
 * in-flow "find by photo" filter that mirrors the wardrobe search affordance without
 * dropping the user out of the composer.
 */
internal suspend fun searchSimilarInCandidates(
    rawFile: File,
    candidates: List<DriveImage>,
    cacheDir: File,
    topK: Int = 50,
): List<EmbeddingService.Match> {
    if (!EmbeddingService.isModelAvailable() || candidates.isEmpty()) return emptyList()
    EmbeddingService.syncIndex(candidates, cacheDir)
    val candidateIds = candidates.map { it.driveId }.toSet()
    return EmbeddingService
        .findSimilar(rawFile, threshold = -1f, topK = candidateIds.size.coerceAtLeast(1))
        .filter { it.driveId in candidateIds }
        .take(topK)
}

private fun bestTokenScore(token: String, tagStrings: List<String>): Float {
    var best = 0f
    for (raw in tagStrings) {
        val tag = normalizeForSearch(raw)
        if (tag.isEmpty()) continue
        if (tag.contains(token)) {
            // Whole-word/exact matches outrank substring matches.
            val score = if (tag == token) 1.0f else 0.9f
            if (score > best) best = score
            continue
        }
        val tol = when {
            token.length <= 3 -> 0
            token.length <= 5 -> 1
            else -> 2
        }
        if (tol == 0) continue
        // Compare token against each whitespace-separated word in the tag.
        for (word in tag.split(FUZZY_TOKEN_SPLIT)) {
            if (word.isEmpty()) continue
            if (kotlin.math.abs(word.length - token.length) > tol) continue
            val d = levenshtein(token, word)
            if (d <= tol) {
                val score = 0.75f - 0.1f * d
                if (score > best) best = score
            }
        }
    }
    return best
}

/** Lowercase + collapse German umlauts/ß into ASCII so "weiss" and "weiß" compare equal. */
private fun normalizeForSearch(s: String): String = s.lowercase()
    .replace('ä', 'a').replace('ö', 'o').replace('ü', 'u').replace("ß", "ss")

private fun tagsToStrings(t: ClothingTags): List<String> = buildList {
    if (t.label.isNotBlank()) add(t.label)
    if (t.type.isNotBlank()) add(t.type)
    if (t.category.isNotBlank()) add(t.category)
    addAll(t.uses); addAll(t.colors); addAll(t.seasonality)
    addAll(t.aesthetic); addAll(t.fit); addAll(t.material); addAll(t.pattern)
}.filter { it.isNotBlank() }

private fun levenshtein(a: String, b: String): Int {
    val costs = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        var nw = i - 1
        costs[0] = i
        for (j in 1..b.length) {
            val cj = minOf(
                1 + minOf(costs[j], costs[j - 1]),
                if (a[i - 1] == b[j - 1]) nw else nw + 1,
            )
            nw = costs[j]
            costs[j] = cj
        }
    }
    return costs[b.length]
}
