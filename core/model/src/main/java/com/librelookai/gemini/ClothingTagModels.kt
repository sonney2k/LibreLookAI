package com.librelookai.gemini

/**
 * Public Gemini-facing data classes shared across the app (wardrobe items, Room stores, prompt
 * builders). Kept in the `com.librelookai.gemini` package after moving to `:core:model` so the
 * ~50 existing import sites stay untouched; the response DTOs remain in the app module's
 * `gemini/GeminiModels.kt`.
 */

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
