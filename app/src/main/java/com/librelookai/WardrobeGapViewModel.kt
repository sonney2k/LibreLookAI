package com.librelookai

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WardrobeGapUiState(
    val isAnalyzing: Boolean = false,
    val analysis: GapAnalysis? = null,
    val error: String? = null,
)

class WardrobeGapViewModel(app: Application) : AndroidViewModel(app) {

    private val gemini = GeminiRepository(app)
    private val gson   = Gson()

    private val _state = MutableStateFlow(WardrobeGapUiState())
    val state: StateFlow<WardrobeGapUiState> = _state.asStateFlow()

    fun clearError()  = _state.update { it.copy(error = null) }
    fun clearResult() = _state.update { it.copy(analysis = null, error = null) }

    fun analyze(images: List<DriveImage>, prefs: UserPreferences?) {
        if (images.isEmpty()) {
            _state.update { it.copy(error = "Add items to your wardrobe first.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isAnalyzing = true, analysis = null, error = null) }

            val prompt = buildGapPrompt(images, prefs)
            Log.d("GapVM", "Gap prompt length: ${prompt.length} chars")

            val raw = gemini.generateText(prompt)
            if (raw == null) {
                _state.update { it.copy(isAnalyzing = false, error = "Gemini did not respond.") }
                return@launch
            }

            val json = raw.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            val result = runCatching { gson.fromJson(json, GapAnalysis::class.java) }.getOrNull()
            if (result == null || result.suggestions.isEmpty()) {
                Log.w("GapVM", "Failed to parse gap analysis: $json")
                _state.update { it.copy(isAnalyzing = false, error = "Could not parse Gemini response.") }
                return@launch
            }

            _state.update { it.copy(isAnalyzing = false, analysis = result) }
        }
    }
}

// ---------- Prompt builder ----------

private fun buildGapPrompt(images: List<DriveImage>, prefs: UserPreferences?): String {
    val age = prefs?.yearOfBirth?.let { java.time.LocalDate.now().year - it }

    val wardrobeJson = images.joinToString(",", "[", "]") { img ->
        val t = img.tags
        if (t == null) {
            """{"id":"${img.driveId}","name":"${img.name}","tags":null}"""
        } else {
            val uses        = t.uses.joinToString(",", "[", "]") { "\"$it\"" }
            val colors      = t.colors.joinToString(",", "[", "]") { "\"$it\"" }
            val seasonality = t.seasonality.joinToString(",", "[", "]") { "\"$it\"" }
            val aesthetic   = t.aesthetic.joinToString(",", "[", "]") { "\"$it\"" }
            val fit         = t.fit.joinToString(",", "[", "]") { "\"$it\"" }
            val material    = t.material.joinToString(",", "[", "]") { "\"$it\"" }
            val pattern     = t.pattern.joinToString(",", "[", "]") { "\"$it\"" }
            """{"id":"${img.driveId}","name":"${img.name}","tags":{"type":"${t.type}","category":"${t.category}","uses":$uses,"colors":$colors,"seasonality":$seasonality,"aesthetic":$aesthetic,"fit":$fit,"material":$material,"pattern":$pattern}}"""
        }
    }

    return buildString {
        appendLine("You are a personal stylist and wardrobe analyst.")
        appendLine()
        appendLine("## User Profile")
        appendLine("- Gender: ${prefs?.gender?.takeIf { it.isNotEmpty() } ?: "not specified"}")
        appendLine("- Age: ${age?.toString() ?: "not specified"}")
        appendLine("- Outfit preferences: ${prefs?.preferences?.takeIf { it.isNotEmpty() } ?: "none provided"}")
        appendLine()
        appendLine("## Current Wardrobe (${images.size} items)")
        appendLine(wardrobeJson)
        appendLine()
        appendLine("## Task")
        appendLine("Analyze the wardrobe above. Identify the top 3 missing foundational pieces that would:")
        appendLine("1. Unlock the highest number of new complete outfit combinations")
        appendLine("2. Complement the user's existing color palette and aesthetic")
        appendLine("3. Fill genuine category gaps (e.g. missing footwear type, missing layering piece)")
        appendLine()
        appendLine("For each suggestion, estimate how many additional complete outfits it would enable.")
        appendLine("The 'reason' field must be phrased as a concrete, user-facing observation, e.g.:")
        appendLine("  \"You have 4 formal shirts and 3 blazers, but no formal footwear to complete those looks.\"")
        appendLine()
        appendLine("Respond with ONLY a valid JSON object — no markdown, no extra text:")
        append("""{"suggestions":[{"missingItem":"<item name>","category":"<category>","colors":["<color1>","<color2>"],"reason":"<user-facing observation>","outfitCount":<integer>},...],"summary":"<1-2 sentence overall wardrobe assessment>"}""")
        appendLine()
        appendLine()
        appendLine("IMPORTANT: Write all user-facing text fields (reason, summary) in ${AppLanguage.toGeminiName(prefs?.language ?: AppLanguage.ENGLISH)}.")
    }
}
