package com.librelookai.outfit

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.librelookai.data.model.Outfit
import com.librelookai.gemini.PromptKey
import com.librelookai.gemini.PromptStore
import com.librelookai.gemini.UsageCategory
import com.librelookai.gemini.generateText
import com.librelookai.gemini.searchFashionTrends
import com.librelookai.settings.AppLanguage
import com.librelookai.settings.UserPreferences
import com.librelookai.wardrobe.DriveImage
import com.librelookai.weather.WeatherData
import java.util.UUID
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// AI-composer response shapes. NOTE: Gson constructs these via unsafe allocation — it never
// calls the Kotlin constructor and never applies default values. Any field missing from (or
// null in) the JSON stays null regardless of a non-null declared type, which crashes on access.
// So everything Gemini might omit is declared nullable and coalesced at every read site.
internal data class ComposerSlotAssignment(val slotId: String? = null, val itemId: String? = null)
internal data class ComposerVariant(
    val slots: List<ComposerSlotAssignment>? = null,
    val name: String? = null,
    val description: String? = null,
    val reason: String? = null,
    val tags: List<String>? = null,
    // Legacy fallback: older prompt shape returned flat itemIds.
    val itemIds: List<String>? = null,
)
private data class ComposerMultiResp(
    val suggestions: List<ComposerVariant>? = null,
    // Back-compat: the single-suggestion schema is a top-level ComposerVariant.
    val slots: List<ComposerSlotAssignment>? = null,
    val name: String? = null,
    val description: String? = null,
    val reason: String? = null,
    val tags: List<String>? = null,
    val itemIds: List<String>? = null,
)

/**
 * Parses a raw Gemini composer response (optionally fenced in ```json) into the list of
 * candidate [ComposerVariant]s, tolerating both the multi-suggestion schema and the legacy
 * single-suggestion / flat-itemIds shapes. Returns an empty list for any response that is
 * unparseable or carries no usable assignments — callers surface that as a parse error rather
 * than crashing. Pure (no VM state) so it can be unit-tested directly.
 */
internal fun parseComposerVariants(raw: String, gson: Gson = Gson()): List<ComposerVariant> {
    val json = raw.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val parsed = runCatching { gson.fromJson(json, ComposerMultiResp::class.java) }.getOrNull()
        ?: return emptyList()
    val variants: List<ComposerVariant> = when {
        !parsed.suggestions.isNullOrEmpty() -> parsed.suggestions
        else -> listOf(ComposerVariant(
            slots = parsed.slots,
            name = parsed.name,
            description = parsed.description,
            reason = parsed.reason,
            tags = parsed.tags,
            itemIds = parsed.itemIds,
        ))
    }
    return variants.filter { !it.slots.isNullOrEmpty() || !it.itemIds.isNullOrEmpty() }
}

internal fun OutfitsViewModel.openComposer(
        seedItemIds: Set<String>,
        images: List<DriveImage>,
        prefs: UserPreferences?,
        initialName: String = "",
        initialDescription: String = "",
        initialTags: List<String> = emptyList(),
        editingStyleId: String? = null,
        defaultSourceFolderId: String? = null,
        tripContext: TripContext? = null,
    ) {
        val ids = seedItemIds.toList()
        val sourceFolders = defaultSourceFolderId?.let { setOf(it) } ?: emptySet()
        val byId = images.associateBy { it.driveId }
        val slots: List<OutfitSlot> = if (ids.isEmpty()) {
            // Accessories and one-piece are off by default for scratch outfits;
            // user adds them via "+ Add slot".
            Layer.values()
                .filter { it != Layer.Accessory && it != Layer.OnePiece }
                .map { layer ->
                    OutfitSlot(UUID.randomUUID().toString(), layer, null, false)
                }
        } else {
            ids.map { id ->
                val img = byId[id]
                val layer = img?.let { layerFor(it) } ?: Layer.Top
                OutfitSlot(UUID.randomUUID().toString(), layer, id, true)
            }
        }
        // Composer always opens in EDIT mode — both for new and existing outfits.
        // The "Fullscreen" action in the header opens a read-only viewer separately.
        val mode = ComposerMode.EDIT
        // Trip-day flows seed manual weather from the forecast so the AI knows the conditions
        // when generating alternatives, and pre-select the trip's vibes.
        val seedWeatherMode = if (tripContext?.dayForecast != null) ComposerWeatherMode.MANUAL else ComposerWeatherMode.AUTO
        val seedSeason  = tripContext?.let { deriveSeasonFromIsoDate(it.tripStartDate, it.dayIndex) } ?: ""
        val seedTempC   = tripContext?.dayForecast?.let { ((it.minTempC + it.maxTempC) / 2f).toInt() }
        val seedPrecip  = tripContext?.dayForecast?.weatherCode?.let { precipForWmoCode(it) } ?: ""
        val seedVibes   = tripContext?.vibes ?: emptySet()
        _state.update {
            it.copy(
                isComposerOpen              = true,
                composerEditingOutfitId      = editingStyleId,
                composerItemIds             = ids,
                composerSlots               = slots,
                composerInitialItemIds      = slots.map { it.selectedItemId },
                composerMode                = mode,
                composerWeatherMode         = seedWeatherMode,
                composerManualSeason        = seedSeason,
                composerManualTempC         = seedTempC,
                composerManualPrecip        = seedPrecip,
                composerVibes               = seedVibes,
                composerTripContext         = tripContext,
                composerName                = initialName,
                composerDescription         = initialDescription,
                composerTags                = editingStyleId?.let { id ->
                    it.outfits.find { o -> o.id == id }?.tags ?: initialTags
                } ?: initialTags,
                composerFeedback            = "",
                composerFeedbackHistory     = emptyList(),
                composerReason              = "",
                composerSourceFolderIds     = sourceFolders,
                isComposerEnhancing         = false,
                composerError               = null,
                composerAiSuggestedName     = "",
                composerAiSuggestedDescription = "",
                composerAiSuggestedTags     = emptyList(),
                isSaveDialogOpen            = false,
            )
        }
    }

    /** Toggle whether [folderId] is included in the composer's source-closet filter. */
internal fun OutfitsViewModel.toggleComposerSourceFolder(folderId: String) {
        _state.update { s ->
            val next = s.composerSourceFolderIds.toMutableSet()
            if (!next.add(folderId)) next.remove(folderId)
            s.copy(composerSourceFolderIds = next)
        }
    }

    /** Opens the composer seeded with the union of all items from the currently-selected styles. */
internal fun OutfitsViewModel.openComposerFromSelectedOutfits(images: List<DriveImage>, prefs: UserPreferences?) {
        val selected = _state.value.outfits.filter { it.id in _state.value.selectedOutfitIds }
        if (selected.size < 2) return
        val unionIds = selected.flatMap { it.itemIds }.toSet()
        val suggestedName = selected.joinToString(" + ") { it.name.ifBlank { "Outfit" } }
            .take(60)
        openComposer(
            seedItemIds = unionIds,
            images = images,
            prefs = prefs,
            initialName = suggestedName,
        )
        _state.update { it.copy(selectedOutfitIds = emptySet()) }
    }

internal fun OutfitsViewModel.closeComposer() = _state.update {
        it.copy(
            isComposerOpen              = false,
            composerEditingOutfitId      = null,
            composerItemIds             = emptyList(),
            composerSlots               = emptyList(),
            composerInitialItemIds      = emptyList(),
            composerFeedback            = "",
            composerFeedbackHistory     = emptyList(),
            composerReason              = "",
            composerError               = null,
            isSaveDialogOpen            = false,
            composerAiSuggestedName     = "",
            composerAiSuggestedDescription = "",
            composerAiSuggestedTags     = emptyList(),
            composerSuggestions         = emptyList(),
            composerSuggestionIndex     = 0,
            composerSuggestionsViewerOpen = false,
            composerTripContext         = null,
        )
    }

internal fun OutfitsViewModel.toggleComposerVibe(vibe: String) = _state.update { s ->
        val next = s.composerVibes.toMutableSet()
        if (!next.add(vibe)) next.remove(vibe)
        s.copy(composerVibes = next)
    }
internal fun OutfitsViewModel.setComposerWeatherMode(mode: ComposerWeatherMode) = _state.update { it.copy(composerWeatherMode = mode) }
internal fun OutfitsViewModel.setComposerManualSeason(season: String) = _state.update { it.copy(composerManualSeason = season) }
internal fun OutfitsViewModel.setComposerManualTempC(tempC: Int?) = _state.update { it.copy(composerManualTempC = tempC) }
internal fun OutfitsViewModel.setComposerManualPrecip(p: String) = _state.update { it.copy(composerManualPrecip = p) }
internal fun OutfitsViewModel.setComposerForecastDate(date: String?) = _state.update { it.copy(composerForecastDate = date) }
internal fun OutfitsViewModel.setComposerSuggestionCount(n: Int) = _state.update {
        it.copy(composerSuggestionCount = n.coerceIn(1, 10))
    }
internal fun OutfitsViewModel.updateComposerName(s: String) = _state.update { it.copy(composerName = s) }
internal fun OutfitsViewModel.updateComposerDescription(s: String) = _state.update { it.copy(composerDescription = s) }
internal fun OutfitsViewModel.addComposerTag(tag: String) {
        val t = tag.trim()
        if (t.isEmpty()) return
        _state.update { s ->
            if (s.composerTags.any { it.equals(t, ignoreCase = true) }) s
            else s.copy(composerTags = s.composerTags + t)
        }
    }
internal fun OutfitsViewModel.removeComposerTag(tag: String) = _state.update { s -> s.copy(composerTags = s.composerTags - tag) }
internal fun OutfitsViewModel.updateComposerFeedback(s: String) = _state.update { it.copy(composerFeedback = s) }

    /** Asks Gemini to complete the composer draft into a full outfit. */
internal fun OutfitsViewModel.enhanceComposerWithAi(
        prefs: UserPreferences?,
        weather: WeatherData?,
        images: List<DriveImage>,
    ) {
        val s = _state.value
        if (images.isEmpty()) {
            _state.update { it.copy(composerError = "No wardrobe items to compose from.") }
            return
        }
        val feedbackAdd = s.composerFeedback.trim()
        val history = if (feedbackAdd.isNotEmpty()) s.composerFeedbackHistory + feedbackAdd else s.composerFeedbackHistory
        val suggestionCount = s.composerSuggestionCount.coerceIn(1, 10)
        // Register a one-tap retry so the global AI-failure dialog can re-run the composition.
        com.librelookai.gemini.AiRetry.action = { enhanceComposerWithAi(prefs, weather, images) }
        _state.update {
            it.copy(
                isComposerEnhancing     = true,
                composerError           = null,
                composerFeedback        = "",
                composerFeedbackHistory = history,
                composerSuggestions     = emptyList(),
                composerSuggestionIndex = 0,
            )
        }
        viewModelScope.launch {
            val countryCode = deviceCountryCode()
            val region = listOfNotNull(weather?.cityName?.takeIf { it.isNotEmpty() }, countryCode).joinToString(", ")
            val fashionTrends = try {
                gemini.searchFashionTrends(region, UsageCategory.OUTFIT_COMPOSE)
            } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                _state.update { it.copy(isComposerEnhancing = false) }
                return@launch
            }
            val prefString = prefs?.preferences.orEmpty()
            val slots = s.composerSlots
            val prompt = buildComposerPrompt(
                preamble         = PromptStore.get(getApplication(), PromptKey.COMPOSER),
                prefs            = prefs,
                prefOverride     = prefString,
                weatherAuto      = if (s.composerWeatherMode == ComposerWeatherMode.AUTO) weather else null,
                weatherManual    = if (s.composerWeatherMode == ComposerWeatherMode.MANUAL) Triple(
                    s.composerManualSeason, s.composerManualTempC, s.composerManualPrecip
                ) else null,
                vibes            = s.composerVibes,
                slots            = slots,
                images           = images,
                countryCode      = countryCode,
                cityName         = weather?.cityName,
                fashionTrends    = fashionTrends,
                feedbackHistory  = history,
                language         = prefs?.language ?: AppLanguage.ENGLISH,
                suggestionCount  = suggestionCount,
                considerationsOverride = s.composerConsiderationsOverride,
                tripContext      = s.composerTripContext,
            )
            Log.d("StylesVM", "Composer prompt length: ${prompt.length} chars")
            val raw = try {
                gemini.generateText(prompt, UsageCategory.OUTFIT_COMPOSE, bulkItems = suggestionCount, notify = true)
            } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                _state.update { it.copy(isComposerEnhancing = false) }
                return@launch
            }
            if (raw == null) {
                _state.update { it.copy(isComposerEnhancing = false, composerError = "Gemini did not respond.") }
                return@launch
            }
            val variants = parseComposerVariants(raw, gson)
            if (variants.isEmpty()) {
                Log.w("StylesVM", "Failed to parse composer response: $raw")
                _state.update { it.copy(isComposerEnhancing = false, composerError = "Could not parse Gemini response.") }
                return@launch
            }
            val knownIds = images.map { it.driveId }.toSet()
            val byImageId = images.associateBy { it.driveId }
            val currentSlots = s.composerSlots

            fun resolveAssignments(v: ComposerVariant): Map<String, String> {
                val direct = v.slots.orEmpty()
                    .filter { !it.slotId.isNullOrEmpty() && it.itemId in knownIds }
                    .associate { it.slotId!! to it.itemId!! }
                if (direct.isNotEmpty()) return direct
                // Legacy: distribute itemIds across empty/unlocked slots by category.
                val unlockedSlotIds = currentSlots.filter { !(it.isLocked && it.selectedItemId != null) }.map { it.id }.toSet()
                val mutableSlots = currentSlots.toMutableList()
                val mapping = mutableMapOf<String, String>()
                for (candidateId in v.itemIds.orEmpty().filter { it in knownIds }) {
                    val img = byImageId[candidateId] ?: continue
                    val cat = layerFor(img) ?: Layer.Top
                    val idx = mutableSlots.indexOfFirst {
                        it.id in unlockedSlotIds && it.category == cat &&
                            it.selectedItemId == null && it.id !in mapping
                    }
                    if (idx >= 0) mapping[mutableSlots[idx].id] = candidateId
                }
                return mapping
            }

            val composerSuggestions = variants.map { v ->
                ComposerSuggestion(
                    slotAssignments = resolveAssignments(v),
                    name = v.name.orEmpty(),
                    description = v.description.orEmpty(),
                    reason = v.reason.orEmpty(),
                    tags = v.tags.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                )
            }

            val first = composerSuggestions.first()
            val updatedSlots = applyComposerSuggestionToSlots(currentSlots, first.slotAssignments)
            val mergedIds = updatedSlots.mapNotNull { it.selectedItemId }.distinct()
            _state.update {
                it.copy(
                    isComposerEnhancing            = false,
                    composerSlots                  = updatedSlots,
                    composerItemIds                = mergedIds,
                    composerAiSuggestedName        = first.name,
                    composerAiSuggestedDescription = first.description,
                    composerAiSuggestedTags        = first.tags,
                    composerReason                 = first.reason,
                    composerSuggestions            = composerSuggestions,
                    composerSuggestionIndex        = 0,
                    // Auto-open the fullscreen suggestion swiper when there's more than one option.
                    composerSuggestionsViewerOpen  = composerSuggestions.size > 1,
                )
            }
        }
    }

internal fun OutfitsViewModel.closeComposerSuggestionsViewer() = _state.update { it.copy(composerSuggestionsViewerOpen = false) }
internal fun OutfitsViewModel.openComposerSuggestionsViewer() = _state.update {
        if (it.composerSuggestions.size > 1) it.copy(composerSuggestionsViewerOpen = true) else it
    }

    /**
     * "Use this outfit" path from the fullscreen suggestion viewer. Applies the picked
     * suggestion to the composer like [showComposerSuggestionAt] AND drops the other
     * alternatives so the inline swiper / re-open affordance disappear. The user has chosen
     * the one to keep — the rest are noise from here on.
     */
internal fun OutfitsViewModel.commitComposerSuggestion(index: Int) {
        val s = _state.value
        if (index !in s.composerSuggestions.indices) return
        val pick = s.composerSuggestions[index]
        val updatedSlots = applyComposerSuggestionToSlots(s.composerSlots, pick.slotAssignments)
        val mergedIds = updatedSlots.mapNotNull { it.selectedItemId }.distinct()
        _state.update {
            it.copy(
                composerSlots                  = updatedSlots,
                composerItemIds                = mergedIds,
                composerAiSuggestedName        = pick.name,
                composerAiSuggestedDescription = pick.description,
                composerAiSuggestedTags        = pick.tags,
                composerReason                 = pick.reason,
                composerSuggestions            = listOf(pick),
                composerSuggestionIndex        = 0,
                composerSuggestionsViewerOpen  = false,
            )
        }
    }

    /**
     * Switches the composer to a different AI-generated variant. Locked slots are preserved
     * (so the user can lock a piece they like, then keep browsing). Empty slots in the chosen
     * variant remain empty.
     */
internal fun OutfitsViewModel.showComposerSuggestionAt(index: Int) {
        val s = _state.value
        if (index !in s.composerSuggestions.indices) return
        val pick = s.composerSuggestions[index]
        val updatedSlots = applyComposerSuggestionToSlots(s.composerSlots, pick.slotAssignments)
        val mergedIds = updatedSlots.mapNotNull { it.selectedItemId }.distinct()
        _state.update {
            it.copy(
                composerSuggestionIndex        = index,
                composerSlots                  = updatedSlots,
                composerItemIds                = mergedIds,
                composerAiSuggestedName        = pick.name,
                composerAiSuggestedDescription = pick.description,
                composerAiSuggestedTags        = pick.tags,
                composerReason                 = pick.reason,
            )
        }
    }

internal fun OutfitsViewModel.applyComposerSuggestionToSlots(
        slots: List<OutfitSlot>,
        assignments: Map<String, String>,
    ): List<OutfitSlot> = slots.map { slot ->
        val lockedFilled = slot.isLocked && slot.selectedItemId != null
        if (lockedFilled) slot
        else {
            val newItemId = assignments[slot.id]
            if (newItemId != null) slot.copy(selectedItemId = newItemId, isLocked = false)
            else slot
        }
        // A one-piece and a separate top/bottom may coexist (layering), so we keep whatever the
        // model assigned without dropping either side.
    }

internal fun OutfitsViewModel.clearComposerError() = _state.update { it.copy(composerError = null) }

internal fun OutfitsViewModel.setComposerMode(mode: ComposerMode) = _state.update { it.copy(composerMode = mode) }

internal fun OutfitsViewModel.addSlot(category: Layer) = _state.update { s ->
        s.copy(composerSlots = s.composerSlots + OutfitSlot(UUID.randomUUID().toString(), category, null, false))
    }

internal fun OutfitsViewModel.removeSlot(slotId: String) = _state.update { s ->
        val slot = s.composerSlots.find { it.id == slotId }
        val newSlots = s.composerSlots.filter { it.id != slotId }
        val newIds = slot?.selectedItemId?.let { id -> s.composerItemIds - id } ?: s.composerItemIds
        s.copy(composerSlots = newSlots, composerItemIds = newIds)
    }

internal fun OutfitsViewModel.setSlotItem(slotId: String, itemId: String?) = _state.update { s ->
        // A one-piece (dress) and a separate top/bottom may coexist — layering is allowed — so
        // filling a slot no longer clears any other slot.
        val newSlots = s.composerSlots.map { slot ->
            if (slot.id == slotId) slot.copy(selectedItemId = itemId, isLocked = itemId != null)
            else slot
        }
        val slotItemIds = newSlots.mapNotNull { it.selectedItemId }.distinct()
        s.copy(composerSlots = newSlots, composerItemIds = slotItemIds)
    }

internal fun OutfitsViewModel.toggleSlotLock(slotId: String) = _state.update { s ->
        s.copy(composerSlots = s.composerSlots.map { slot ->
            if (slot.id == slotId && slot.selectedItemId != null) slot.copy(isLocked = !slot.isLocked)
            else slot
        })
    }

