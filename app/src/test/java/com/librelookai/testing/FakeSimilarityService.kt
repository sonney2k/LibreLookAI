package com.librelookai.testing

import com.librelookai.ml.EmbeddingService
import com.librelookai.ml.SimilarityService
import com.librelookai.wardrobe.DriveImage
import java.io.File

/**
 * Scriptable [SimilarityService] for the § 8 pipeline suites. Both availability flags default
 * to `false` — the pre-seam static's behavior in a JVM test environment — so existing suites
 * exercise the gate-off routing unchanged; flip them plus [similarityResult] to drive the
 * dedupe-gate / local-bg-review branches.
 */
class FakeSimilarityService : SimilarityService {

    var modelAvailable = false
    var segmenterAvailable = false

    /** Scripted result for every [findSimilarWithDebug] query; null = no matches computable. */
    var similarityResult: EmbeddingService.SimilarityResult? = null

    /** The cross-closet pools passed to [syncIndex], in order. */
    val syncedPools = mutableListOf<List<DriveImage>>()

    /** Files passed to [findSimilarWithDebug], in order. */
    val queries = mutableListOf<File>()

    override fun isModelAvailable(): Boolean = modelAvailable

    override fun isSegmenterAvailable(): Boolean = segmenterAvailable

    override suspend fun syncIndex(
        images: List<DriveImage>,
        cacheDir: File,
        onProgress: ((done: Int, total: Int) -> Unit)?,
    ): Int {
        syncedPools += images
        return 0
    }

    override suspend fun findSimilarWithDebug(
        file: File,
        threshold: Float,
        excludeIds: Set<String>,
        topK: Int,
        processedOutputDir: File?,
    ): EmbeddingService.SimilarityResult? {
        queries += file
        return similarityResult
    }

    companion object {
        /** A minimal scripted result whose matches point at [driveIds]. */
        fun result(vararg driveIds: String, score: Float = 0.95f) =
            EmbeddingService.SimilarityResult(
                matches = driveIds.map { EmbeddingService.Match(it, score) },
                processedPath = null,
                segmented = false,
                hist = FloatArray(0),
                vec = FloatArray(0),
                pHash = 0L,
            )
    }
}
