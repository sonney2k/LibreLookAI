package com.librelookai.ml

import com.librelookai.wardrobe.DriveImage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * The on-device similarity seam (the § 3 seam pattern applied to `core/ml`): the subset of
 * [EmbeddingService] that injected consumers call, virtual so they are fake-testable — added
 * to close the § 8 gap on `ItemIngestionPipeline`'s dedupe-gate / local-bg-review branches.
 *
 * [EmbeddingService] implements it; the composable debug surfaces (`MatchDebug`,
 * `LocalBgRemovalScreen`) and `StaticPreferenceMirrors` deliberately stay on the static
 * object (composables are unreachable by constructor injection — the § 7 flags rationale).
 * Same standing rule as [com.librelookai.data.drive.DriveService]: when a new *class* needs
 * an `EmbeddingService` call, absorb it here as a member — don't take the object.
 */
interface SimilarityService {

    /** True when the embedding model is initialized and loadable. */
    fun isModelAvailable(): Boolean

    /** True when the interactive segmenter is initialized and loadable. */
    fun isSegmenterAvailable(): Boolean

    /** See [EmbeddingService.syncIndex]. */
    suspend fun syncIndex(
        images: List<DriveImage>,
        cacheDir: File,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): Int

    /** See [EmbeddingService.findSimilarWithDebug]. */
    suspend fun findSimilarWithDebug(
        file: File,
        threshold: Float,
        excludeIds: Set<String> = emptySet(),
        topK: Int = 20,
        processedOutputDir: File? = null,
    ): EmbeddingService.SimilarityResult?
}

@Module
@InstallIn(SingletonComponent::class)
object SimilarityServiceModule {
    @Provides
    @Singleton
    fun provideSimilarityService(): SimilarityService = EmbeddingService
}
