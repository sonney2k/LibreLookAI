package com.librelookai.outfit

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.librelookai.gemini.PromptKey
import com.librelookai.gemini.PromptStore
import com.librelookai.gemini.UsageCategory
import com.librelookai.settings.AiConsiderations
import com.librelookai.settings.UserPreferences
import com.librelookai.wardrobe.DriveImage
import com.librelookai.weather.WeatherData
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun OutfitsViewModel.openPredictionSetup(
        defaultSourceFolderId: String?,
        source: PredictionSetupSource = PredictionSetupSource.OUTFITS_LIST,
    ) {
        _state.update {
            // From the composer, keep the source closets already chosen there; otherwise seed
            // from the provided default (the active closet, or all closets when null).
            val sourceFolders = if (source == PredictionSetupSource.COMPOSER) it.composerSourceFolderIds
                else defaultSourceFolderId?.let { id -> setOf(id) } ?: emptySet()
            it.copy(
                isPredictionSetupOpen          = true,
                predictionSetupSource          = source,
                composerFeedback               = "",
                composerVibes                  = emptySet(),
                composerWeatherMode            = ComposerWeatherMode.AUTO,
                composerManualSeason           = "",
                composerManualTempC            = null,
                composerManualPrecip           = "",
                composerSourceFolderIds        = sourceFolders,
                composerConsiderationsOverride = null,
            )
        }
    }

    /**
     * Toggle one of the per-invocation AI considerations from the Find/Create-with-AI dialog.
     * Seeds the override from [prefsDefault] on first interaction so unchanged fields keep
     * matching the user's saved settings.
     */
internal fun OutfitsViewModel.setComposerConsideration(prefsDefault: AiConsiderations, transform: (AiConsiderations) -> AiConsiderations) {
        _state.update {
            val base = it.composerConsiderationsOverride ?: prefsDefault
            it.copy(composerConsiderationsOverride = transform(base))
        }
    }

internal fun OutfitsViewModel.closePredictionSetup() = _state.update { it.copy(isPredictionSetupOpen = false) }

    /** Reset every field steered by the Tune-AI dialog back to its default. */
internal fun OutfitsViewModel.resetComposerAi() = _state.update {
        it.copy(
            composerFeedback              = "",
            composerVibes                 = emptySet(),
            composerWeatherMode           = ComposerWeatherMode.AUTO,
            composerManualSeason          = "",
            composerManualTempC           = null,
            composerManualPrecip          = "",
            composerForecastDate          = null,
            composerConsiderationsOverride = null,
        )
    }

    /**
     * Called from the prediction-setup dialog's "Find" CTA. Snapshots the user's goal text as
     * the first refinement entry so the rest of the prediction/refinement loop uses one path.
     */
internal fun OutfitsViewModel.submitPredictionSetup(prefs: UserPreferences?, weather: WeatherData?, images: List<DriveImage>) {
        val s = _state.value
        val goal = s.composerFeedback.trim()
        val history = if (goal.isNotEmpty()) listOf(goal) else emptyList()
        _state.update {
            it.copy(
                isPredictionSetupOpen = false,
                composerFeedback      = "",
                feedbackHistory       = history,
            )
        }
        if (s.predictionSetupSource == PredictionSetupSource.COMPOSER) {
            enhanceComposerWithAi(prefs, weather, images)
        } else {
            doTriggerPrediction(prefs, weather, images, history)
        }
    }

internal fun OutfitsViewModel.submitPresetPrediction(preset: String, prefs: UserPreferences?, weather: WeatherData?, images: List<DriveImage>) {
        val history = _state.value.feedbackHistory + preset
        _state.update { it.copy(feedbackHistory = history) }
        doTriggerPrediction(prefs, weather, images, history)
    }

internal fun OutfitsViewModel.doTriggerPrediction(
        prefs: UserPreferences?,
        weather: WeatherData?,
        images: List<DriveImage>,
        feedbackHistory: List<String>,
    ) {
        val styles = _state.value.outfits
        if (styles.isEmpty()) {
            _state.update { it.copy(predictionError = "No styles to choose from yet.") }
            return
        }
        val setup = _state.value
        val filteredImages = if (setup.composerSourceFolderIds.isEmpty()) images
            else images.filter { it.folderId in setup.composerSourceFolderIds }
        viewModelScope.launch {
            _state.update { it.copy(isPredicting = true, prediction = null, predictionSuggestions = emptyList(), predictionIndex = 0, predictionError = null) }

            val countryCode = deviceCountryCode()
            val region = listOfNotNull(weather?.cityName?.takeIf { it.isNotEmpty() }, countryCode).joinToString(", ")
            val fashionTrends = try {
                gemini.searchFashionTrends(region, UsageCategory.OUTFIT_PREDICT)
            } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                _state.update { it.copy(isPredicting = false) }
                return@launch
            }

            val suggestionCount = setup.composerSuggestionCount.coerceIn(1, 10)
            val prompt = buildPredictionPrompt(
                preamble        = PromptStore.get(getApplication(), PromptKey.PREDICTION),
                prefs           = prefs,
                weather         = weather,
                cityName        = weather?.cityName,
                countryCode     = countryCode,
                fashionTrends   = fashionTrends,
                images          = filteredImages,
                styles          = styles,
                feedbackHistory = feedbackHistory,
                weatherMode     = setup.composerWeatherMode,
                manualSeason    = setup.composerManualSeason,
                manualTempC     = setup.composerManualTempC,
                manualPrecip    = setup.composerManualPrecip,
                vibes           = setup.composerVibes,
                forecastDate    = setup.composerForecastDate,
                suggestionCount = suggestionCount,
                considerationsOverride = setup.composerConsiderationsOverride,
            )
            Log.d("StylesVM", "Prediction prompt length: ${prompt.length} chars")
            prompt.chunked(3000).forEachIndexed { i, chunk ->
                Log.d("StylesVM", "Prompt[$i]: $chunk")
            }

            val raw = try {
                gemini.generateText(prompt, UsageCategory.OUTFIT_PREDICT, bulkItems = suggestionCount)
            } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                _state.update { it.copy(isPredicting = false) }
                return@launch
            }
            if (raw == null) {
                _state.update { it.copy(isPredicting = false, predictionError = "Gemini did not respond.") }
                return@launch
            }

            val json = raw.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            data class PredItem(val outfitId: String? = "", val reason: String? = "")
            data class PredResp(
                val suggestions: List<PredItem>? = null,
                // Backward-compat: older prompt variants returned a single object.
                val outfitId: String? = null,
                val reason: String? = null,
            )
            val result = runCatching { gson.fromJson(json, PredResp::class.java) }.getOrNull()

            val rawItems: List<PredItem> = when {
                result == null -> emptyList()
                !result.suggestions.isNullOrEmpty() -> result.suggestions
                !result.outfitId.isNullOrBlank() -> listOf(PredItem(result.outfitId, result.reason.orEmpty()))
                else -> emptyList()
            }

            // Filter to suggestions that actually exist in the wardrobe; cap at the user-chosen count.
            val styleIds = styles.map { it.id }.toSet()
            val matched: List<OutfitPrediction> = rawItems
                .mapNotNull { p ->
                    val id = p.outfitId?.takeIf { it.isNotBlank() && it in styleIds } ?: return@mapNotNull null
                    OutfitPrediction(id, p.reason.orEmpty())
                }
                .distinctBy { it.outfitId }
                .take(suggestionCount)

            if (matched.isEmpty()) {
                Log.w("StylesVM", "Failed to parse prediction response: $json")
                _state.update { it.copy(isPredicting = false, predictionError = "Could not parse Gemini response.") }
                return@launch
            }

            _state.update {
                it.copy(
                    isPredicting = false,
                    prediction = matched.first(),
                    predictionSuggestions = matched,
                    predictionIndex = 0,
                )
            }
        }
    }

internal fun OutfitsViewModel.clearPrediction() = _state.update {
        it.copy(
            prediction = null,
            predictionError = null,
            predictionSuggestions = emptyList(),
            predictionIndex = 0,
            feedbackHistory = emptyList(),
        )
    }

    /**
     * Switches the currently-shown prediction within the existing suggestion list.
     * Swiping/arrowing through suggestions re-opens the editing view for that pick
     * and discards any in-progress edits (acceptable since this is a browse flow).
     */
internal fun OutfitsViewModel.showPredictionAt(index: Int) {
        val list = _state.value.predictionSuggestions
        if (index !in list.indices) return
        val pred = list[index]
        _state.update { it.copy(predictionIndex = index, prediction = pred) }
    }

