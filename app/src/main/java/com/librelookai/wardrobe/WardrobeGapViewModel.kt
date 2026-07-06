package com.librelookai.wardrobe
import com.librelookai.util.localized
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.librelookai.data.model.GapAnalysis
import com.librelookai.gemini.AiClient
import com.librelookai.gemini.AiResult
import com.librelookai.gemini.AiRetry
import com.librelookai.gemini.getOrNull
import com.librelookai.gemini.PromptKey
import com.librelookai.gemini.PromptStore
import com.librelookai.gemini.UsageCategory
import com.librelookai.settings.AiConsiderations
import com.librelookai.settings.UserPreferences
import com.librelookai.settings.AppLanguage
import com.librelookai.data.model.Outfit

data class WardrobeGapUiState(
    val isAnalyzing: Boolean = false,
    val analysis: GapAnalysis? = null,
    val error: String? = null,
    /** Replacements dialog (Wardrobe → "Suggest alternatives"). Independent of the Gaps tab state. */
    val isReplacementsOpen: Boolean = false,
    val isSuggestingReplacements: Boolean = false,
    val replacementsCount: Int = 0,
    val replacements: GapAnalysis? = null,
    val replacementsError: String? = null,
)

@HiltViewModel
class WardrobeGapViewModel @Inject constructor(
    app: Application,
    private val gemini: AiClient,
    private val aiRetry: AiRetry,
) : AndroidViewModel(app) {
    private val gson   = Gson()

    private val _state = MutableStateFlow(WardrobeGapUiState())
    val state: StateFlow<WardrobeGapUiState> = _state.asStateFlow()

    fun clearError()  = _state.update { it.copy(error = null) }
    fun clearResult() = _state.update { it.copy(analysis = null, error = null) }

    fun clearReplacementsError() = _state.update { it.copy(replacementsError = null) }
    fun closeReplacements() = _state.update {
        it.copy(
            isReplacementsOpen = false,
            isSuggestingReplacements = false,
            replacementsCount = 0,
            replacements = null,
            replacementsError = null,
        )
    }

    fun analyze(images: List<DriveImage>, prefs: UserPreferences?) {
        if (images.isEmpty()) {
            _state.update { it.copy(error = getApplication<Application>().localized().getString(com.librelookai.R.string.error_add_items_first)) }
            return
        }

        aiRetry.action = { analyze(images, prefs) }
        viewModelScope.launch {
            _state.update { it.copy(isAnalyzing = true, analysis = null, error = null) }

            val prompt = buildGapPrompt(PromptStore.get(getApplication(), PromptKey.GAP), images, prefs)
            Log.d("GapVM", "Gap prompt length: ${prompt.length} chars")

            val outcome = gemini.generateText(prompt, UsageCategory.GAP_ANALYSIS, notify = true)
            if (outcome is AiResult.InsufficientCredits) {
                // Global InsufficientCreditsDialog in MainActivity shows the prompt;
                // here we just reset the analyzing flag.
                _state.update { it.copy(isAnalyzing = false) }
                return@launch
            }
            val raw = outcome.getOrNull()
            if (raw == null) {
                _state.update { it.copy(isAnalyzing = false, error = getApplication<Application>().localized().getString(com.librelookai.R.string.error_gemini_no_response)) }
                return@launch
            }

            val json = raw.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            val result = runCatching { gson.fromJson(json, GapAnalysis::class.java) }.getOrNull()
            if (result == null || result.suggestions.isEmpty()) {
                Log.w("GapVM", "Failed to parse gap analysis: $json")
                _state.update { it.copy(isAnalyzing = false, error = getApplication<Application>().localized().getString(com.librelookai.R.string.error_gemini_parse)) }
                return@launch
            }

            _state.update { it.copy(isAnalyzing = false, analysis = result) }
        }
    }

    fun suggestReplacements(
        selected: List<DriveImage>,
        allImages: List<DriveImage>,
        prefs: UserPreferences?,
    ) {
        if (selected.isEmpty()) return

        aiRetry.action = { suggestReplacements(selected, allImages, prefs) }
        // Open the dialog immediately so the user sees a loading state.
        _state.update {
            it.copy(
                isReplacementsOpen = true,
                isSuggestingReplacements = true,
                replacementsCount = selected.size,
                replacements = null,
                replacementsError = null,
            )
        }

        viewModelScope.launch {
            val remaining = allImages.filter { img -> selected.none { it.driveId == img.driveId } }
            val prompt = buildReplacementsPrompt(selected, remaining, prefs)
            Log.d("GapVM", "Replacements prompt length: ${prompt.length} chars")

            val outcome = gemini.generateText(prompt, UsageCategory.REPLACEMENTS, notify = true)
            if (outcome is AiResult.InsufficientCredits) {
                _state.update { it.copy(isSuggestingReplacements = false) }
                return@launch
            }
            val raw = outcome.getOrNull()
            if (raw == null) {
                _state.update {
                    it.copy(
                        isSuggestingReplacements = false,
                        replacementsError = "Gemini did not respond.",
                    )
                }
                return@launch
            }

            val json = raw.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            val result = runCatching { gson.fromJson(json, GapAnalysis::class.java) }.getOrNull()
            if (result == null || result.suggestions.isEmpty()) {
                Log.w("GapVM", "Failed to parse replacements: $json")
                _state.update {
                    it.copy(
                        isSuggestingReplacements = false,
                        replacementsError = "Could not parse Gemini response.",
                    )
                }
                return@launch
            }

            _state.update {
                it.copy(
                    isSuggestingReplacements = false,
                    replacements = result,
                )
            }
        }
    }
}

// ---------- Prompt builders ----------

internal fun buildGapPrompt(preamble: String, images: List<DriveImage>, prefs: UserPreferences?): String {
    val c = prefs?.aiConsiderations ?: AiConsiderations()
    val age = prefs?.yearOfBirth?.let { java.time.LocalDate.now().year - it }

    val wardrobeJson = images.joinToString(",", "[", "]") { it.toPromptJson(c) }

    return buildString {
        appendLine(preamble.trim())
        appendLine()
        val profileLines = buildList {
            if (c.gender) add("- Gender: ${prefs?.gender?.takeIf { it.isNotEmpty() } ?: "not specified"}")
            if (c.age) add("- Age: ${age?.toString() ?: "not specified"}")
            if (c.preferences) add("- Outfit preferences: ${prefs?.preferences?.takeIf { it.isNotEmpty() } ?: "none provided"}")
        }
        if (profileLines.isNotEmpty()) {
            appendLine("## User Profile")
            profileLines.forEach { appendLine(it) }
            appendLine()
        }
        appendLine("## Current Wardrobe (${images.size} items)")
        appendLine(wardrobeJson)
        appendLine()
        appendLine("Respond with ONLY a valid JSON object — no markdown, no extra text:")
        append("""{"suggestions":[{"missingItem":"<item name>","category":"<category>","colors":["<color1>","<color2>"],"reason":"<user-facing observation>","outfitCount":<integer>},...],"summary":"<1-2 sentence overall wardrobe assessment>"}""")
        appendLine()
        appendLine()
        appendLine("IMPORTANT: Write all user-facing text fields (reason, summary) in ${AppLanguage.toGeminiName(prefs?.language ?: AppLanguage.ENGLISH)}.")
    }
}

private fun buildReplacementsPrompt(
    retiring: List<DriveImage>,
    remaining: List<DriveImage>,
    prefs: UserPreferences?,
): String {
    val c = prefs?.aiConsiderations ?: AiConsiderations()
    val age = prefs?.yearOfBirth?.let { java.time.LocalDate.now().year - it }

    val retiringJson  = retiring.joinToString(",", "[", "]") { it.toPromptJson(c) }
    val remainingJson = remaining.joinToString(",", "[", "]") { it.toPromptJson(c) }

    val preamble = """
        You are a personal stylist and wardrobe analyst.

        ## Task
        The user wants to retire the items listed under "Retiring" and is looking for replacements to buy.
        Suggest up to ${retiring.size.coerceAtLeast(3)} replacement pieces that:
        1. Cover the same wardrobe roles as the retiring items (category, formality, season).
        2. Coordinate with the user's REMAINING wardrobe (colors, aesthetic, materials).
        3. Unlock as many complete outfits as possible when combined with the remaining wardrobe.

        Suggest replacements — do NOT recommend the user simply re-buy something they already own among the remaining items.
        The 'reason' field must be a concrete, user-facing observation that references the remaining wardrobe, e.g.:
          "Pairs with your 3 navy blazers and replaces the formal footwear role of the retired oxfords."
    """.trimIndent()

    return buildString {
        appendLine(preamble)
        appendLine()
        val profileLines = buildList {
            if (c.gender) add("- Gender: ${prefs?.gender?.takeIf { it.isNotEmpty() } ?: "not specified"}")
            if (c.age) add("- Age: ${age?.toString() ?: "not specified"}")
            if (c.preferences) add("- Outfit preferences: ${prefs?.preferences?.takeIf { it.isNotEmpty() } ?: "none provided"}")
        }
        if (profileLines.isNotEmpty()) {
            appendLine("## User Profile")
            profileLines.forEach { appendLine(it) }
            appendLine()
        }
        appendLine("## Retiring (${retiring.size} items — suggest replacements for these)")
        appendLine(retiringJson)
        appendLine()
        appendLine("## Remaining Wardrobe (${remaining.size} items — replacements must complement these)")
        appendLine(remainingJson)
        appendLine()
        appendLine("Respond with ONLY a valid JSON object — no markdown, no extra text:")
        append("""{"suggestions":[{"missingItem":"<replacement item name>","category":"<category>","colors":["<color1>","<color2>"],"reason":"<user-facing observation referencing the remaining wardrobe>","outfitCount":<integer>},...],"summary":"<1-2 sentence overall replacement strategy>"}""")
        appendLine()
        appendLine()
        appendLine("IMPORTANT: Write all user-facing text fields (reason, summary) in ${AppLanguage.toGeminiName(prefs?.language ?: AppLanguage.ENGLISH)}.")
    }
}
