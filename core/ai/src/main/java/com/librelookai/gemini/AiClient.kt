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
 * Return semantics are unchanged: **every call returns null on failure** and callers degrade
 * gracefully (the sealed `AiResult` conversion is § 6, a separate slice). `searchFashionTrends`
 * is deliberately absent — callers must go through [FashionTrendsCache].
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
    ): String?

    /** Clothing-tag classification of a single item image, in [language]. */
    suspend fun classifyClothing(imageFile: File, language: String = "English"): ClothingTags?

    /**
     * Sends [imageFile] to Gemini and returns a PNG in [outputDir] with the background removed,
     * or null on any failure — callers fall back to the original.
     */
    suspend fun removeBackground(imageFile: File, outputDir: File, notify: Boolean = false): File?

    /** Virtual try-on: renders [personFiles] wearing [itemFiles] into [outputDir]. */
    suspend fun tryOnOutfit(
        personFiles: List<File>,
        itemFiles: List<File>,
        outputDir: File,
        preferences: String = "",
        notify: Boolean = false,
    ): File?
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AiClientModule {
    @Binds
    abstract fun bindAiClient(impl: GeminiRepository): AiClient
}
