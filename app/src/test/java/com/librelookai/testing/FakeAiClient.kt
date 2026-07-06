package com.librelookai.testing

import com.librelookai.gemini.AiClient
import com.librelookai.gemini.AiErrorReason
import com.librelookai.gemini.AiResult
import com.librelookai.gemini.ClothingTags
import com.librelookai.gemini.UsageCategory
import java.io.File

/**
 * In-memory [AiClient] for fake-based pipeline / use-case / VM tests (refactor § 3 slice 4 /
 * § 8) — the [FakeDriveService] pattern. Reads default to "call failed" (a generic
 * [AiResult.Failure], the AI layer's degrade-gracefully contract) and calls record into public
 * lists, so a test only scripts the surface it exercises; set a `*Result` to succeed, set
 * [outcome] to script a typed non-success (e.g. [AiResult.InsufficientCredits] to exercise a
 * bulk abort — replacing the pre-§ 6 thrown `InsufficientCreditsException`).
 */
open class FakeAiClient : AiClient {

    /** Returned by every call when set, before recording — takes precedence over `*Result`. */
    var outcome: AiResult<Nothing>? = null

    val generatedPrompts = mutableListOf<String>()
    var generateResult: String? = null

    /** Files passed to [classifyClothing], in order. */
    val classifiedFiles = mutableListOf<File>()
    var classifyResult: ClothingTags? = null

    /** Files passed to [removeBackground], in order. */
    val removedBackgrounds = mutableListOf<File>()
    var removeBackgroundResult: File? = null

    /** `(personFiles, itemFiles)` for every [tryOnOutfit] call, in order. */
    val tryOnCalls = mutableListOf<Pair<List<File>, List<File>>>()
    var tryOnResult: File? = null

    private fun <T> resolve(value: T?): AiResult<T> =
        value?.let { AiResult.Success(it) } ?: AiResult.Failure(AiErrorReason.GENERIC)

    override suspend fun generateText(
        prompt: String,
        category: UsageCategory,
        bulkItems: Int,
        notify: Boolean,
    ): AiResult<String> {
        outcome?.let { return it }
        generatedPrompts += prompt
        return resolve(generateResult)
    }

    override suspend fun classifyClothing(imageFile: File, language: String): AiResult<ClothingTags> {
        outcome?.let { return it }
        classifiedFiles += imageFile
        return resolve(classifyResult)
    }

    override suspend fun removeBackground(imageFile: File, outputDir: File, notify: Boolean): AiResult<File> {
        outcome?.let { return it }
        removedBackgrounds += imageFile
        return resolve(removeBackgroundResult)
    }

    override suspend fun tryOnOutfit(
        personFiles: List<File>,
        itemFiles: List<File>,
        outputDir: File,
        preferences: String,
        notify: Boolean,
    ): AiResult<File> {
        outcome?.let { return it }
        tryOnCalls += personFiles to itemFiles
        return resolve(tryOnResult)
    }
}
