package com.librelookai.gemini

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File

/**
 * The AI I/O seam (refactor § 3 slice 4): the Gemini surface the pipelines / use-cases / VMs
 * consume, made virtual so fake-based tests can stand in for Gemini — the [DriveService]
 * pattern. Implemented by [GeminiRepository]; behavior that used to live in same-package
 * extension functions ([tryOnOutfitImpl] et al) is un-fakeable (extensions resolve statically),
 * so each absorbed method is an interface member delegating to the renamed `internal`
 * `<name>Impl` extension in its domain file.
 *
 * Every call returns a sealed [AiResult] (refactor § 6): [AiResult.Success] carries the value;
 * [AiResult.InsufficientCredits] replaced the old thrown
 * `InsufficientCreditsException` (the global top-up dialog still fires repo-side); everything
 * else degrades to [AiResult.NotConfigured] / [AiResult.Failure]. Callers that only degrade use
 * `getOrNull()`. `searchFashionTrends` is deliberately absent — callers must go through
 * [FashionTrendsCache].
 *
 * Grown incrementally — add a method the moment a consumer of the *interface* needs it,
 * never speculatively.
 */
interface AiClient {

    /**
     * Free-form text generation. Logs usage under [category]; [bulkItems] scales the output
     * estimate for bulk prompts. Only user-initiated single calls pass `notify = true`.
     */
    suspend fun generateText(
        prompt: String,
        category: UsageCategory = UsageCategory.OTHER,
        bulkItems: Int = 1,
        notify: Boolean = false,
    ): AiResult<String>

    /** Clothing-tag classification of a single item image, in [language]. */
    suspend fun classifyClothing(imageFile: File, language: String = "English"): AiResult<ClothingTags>

    /**
     * Sends [imageFile] to Gemini and returns a PNG in [outputDir] with the background removed —
     * on any non-[AiResult.Success] outcome callers fall back to the original.
     */
    suspend fun removeBackground(imageFile: File, outputDir: File, notify: Boolean = false): AiResult<File>

    /** Virtual try-on: renders [personFiles] wearing [itemFiles] into [outputDir]. */
    suspend fun tryOnOutfit(
        personFiles: List<File>,
        itemFiles: List<File>,
        outputDir: File,
        preferences: String = "",
        notify: Boolean = false,
    ): AiResult<File>
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AiClientModule {
    @Binds
    abstract fun bindAiClient(impl: GeminiRepository): AiClient
}
