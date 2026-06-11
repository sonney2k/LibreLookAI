package com.librelookai.gemini

import com.google.gson.annotations.SerializedName

// Public data classes (FashionTrends, ClothingTags) moved to :core:model
// (core/model/…/gemini/ClothingTagModels.kt), same package.

// ---------- Response DTOs ----------

internal data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
    val error: GeminiError? = null,
    @SerializedName("usageMetadata") val usageMetadata: GeminiUsageMetadata? = null,
)

internal data class GeminiUsageMetadata(
    @SerializedName("promptTokenCount") val promptTokenCount: Int? = null,
    @SerializedName("candidatesTokenCount") val candidatesTokenCount: Int? = null,
    @SerializedName("totalTokenCount") val totalTokenCount: Int? = null,
)

internal data class GeminiError(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null,
)

internal data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null,
)

internal data class GeminiContent(
    val parts: List<GeminiPart>? = null,
)

internal data class GeminiPart(
    val text: String? = null,
    @SerializedName("inlineData") val inlineData: GeminiInlineData? = null,
)

internal data class GeminiInlineData(
    @SerializedName("mimeType") val mimeType: String? = null,
    val data: String? = null,
)

