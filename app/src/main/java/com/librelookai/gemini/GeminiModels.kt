package com.librelookai.gemini

import com.google.gson.annotations.SerializedName

// ---------- Public data classes ----------

data class FashionTrends(
    val region: String = "",
    @com.google.gson.annotations.SerializedName("trending_colors")
    val trendingColors: List<String> = emptyList(),
    @com.google.gson.annotations.SerializedName("trending_aesthetics")
    val trendingAesthetics: List<String> = emptyList(),
    @com.google.gson.annotations.SerializedName("must_have_items")
    val mustHaveItems: List<String> = emptyList(),
    @com.google.gson.annotations.SerializedName("outdated_items")
    val outdatedItems: List<String> = emptyList(),
)

data class ClothingTags(
    val label: String = "",
    val type: String = "",
    val category: String = "",
    val uses: List<String> = emptyList(),
    val colors: List<String> = emptyList(),
    val seasonality: List<String> = emptyList(),
    val aesthetic: List<String> = emptyList(),
    val fit: List<String> = emptyList(),
    val material: List<String> = emptyList(),
    val pattern: List<String> = emptyList(),
)

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

