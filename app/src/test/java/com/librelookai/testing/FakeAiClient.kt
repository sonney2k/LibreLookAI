package com.librelookai.testing

import com.librelookai.gemini.AiClient
import com.librelookai.gemini.ClothingTags
import com.librelookai.gemini.UsageCategory
import java.io.File

/**
 * In-memory [AiClient] for fake-based pipeline / use-case / VM tests (refactor § 3 slice 4 /
 * § 8) — the [FakeDriveService] pattern. Reads default to "call failed" (null, the AI layer's
 * degrade-gracefully contract) and calls record into public lists, so a test only scripts the
 * surface it exercises; set a `*Result` to succeed, set [error] to fault-inject (e.g.
 * `InsufficientCreditsException` to exercise a bulk abort).
 */
open class FakeAiClient : AiClient {

    /** Thrown by every call when set — takes precedence over the `*Result` fields. */
    var error: Exception? = null

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

    private fun maybeThrow() {
        error?.let { throw it }
    }

    override suspend fun generateText(
        prompt: String,
        category: UsageCategory,
        bulkItems: Int,
        notify: Boolean,
    ): String? {
        maybeThrow()
        generatedPrompts += prompt
        return generateResult
    }

    override suspend fun classifyClothing(imageFile: File, language: String): ClothingTags? {
        maybeThrow()
        classifiedFiles += imageFile
        return classifyResult
    }

    override suspend fun removeBackground(imageFile: File, outputDir: File, notify: Boolean): File? {
        maybeThrow()
        removedBackgrounds += imageFile
        return removeBackgroundResult
    }

    override suspend fun tryOnOutfit(
        personFiles: List<File>,
        itemFiles: List<File>,
        outputDir: File,
        preferences: String,
        notify: Boolean,
    ): File? {
        maybeThrow()
        tryOnCalls += personFiles to itemFiles
        return tryOnResult
    }
}
