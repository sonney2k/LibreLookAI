package com.librelookai.outfit

import android.content.Context
import com.librelookai.data.model.DayForecast
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.OutfitEvent
import com.librelookai.settings.AiConsiderations
import com.librelookai.wardrobe.DriveImage
import kotlinx.coroutines.flow.update

data class OutfitPrediction(val outfitId: String, val reason: String)

/**
 * One AI-generated outfit variant produced by the composer's "Generate with AI" flow.
 * Multiple are returned when the user asks Gemini for more than one suggestion so the
 * composer can let the user swipe between alternatives.
 */
data class ComposerSuggestion(
    /** slotId -> wardrobe item id that the AI picked for that slot. */
    val slotAssignments: Map<String, String>,
    val name: String,
    val description: String,
    val reason: String,
    val tags: List<String>,
)

enum class ComposerWeatherMode { AUTO, MANUAL }

/**
 * Context passed to the composer when editing/creating an outfit that belongs to a [Trip].
 * Drives weather pre-seeding, the composer header ("Day N of {tripName}"), and AI prompt
 * augmentation so refinement / alternatives are aware of the trip's vibes, goal,
 * considerations, and per-day forecast.
 */
data class TripContext(
    val tripId: String,
    val tripName: String,
    val dayIndex: Int,
    val dayForecast: DayForecast? = null,
    val tripStartDate: String = "",
    val considerations: AiConsiderations = AiConsiderations(),
    val vibes: Set<String> = emptySet(),
    val goal: String = "",
)


data class OutfitsUiState(
    val outfits: List<Outfit> = emptyList(),
    /** All wardrobe images across all locations, for resolving style item icons. */
    val wardrobeImages: List<DriveImage> = emptyList(),
    val isLoading: Boolean = false,
    // Predict existing style
    val isPredicting: Boolean = false,
    val prediction: OutfitPrediction? = null,
    val predictionError: String? = null,
    /** When Gemini returns several outfit picks the user can slide through, the full list lives here. */
    val predictionSuggestions: List<OutfitPrediction> = emptyList(),
    val predictionIndex: Int = 0,
    // Refinement feedback (prediction loop)
    val feedbackHistory: List<String> = emptyList(),
    /**
     * Calendar wear history (kept in sync from [OutfitEventsViewModel]), fed into the
     * prediction/composer prompts as a taste signal. See [buildWearHistorySummary].
     */
    val wearHistory: List<OutfitEvent> = emptyList(),
    // After saving a style, offer to wear it immediately
    val pendingWearOutfitId: String? = null,
    /** Outfit id the list screen should scroll into view (and briefly highlight) when set. */
    val pendingScrollOutfitId: String? = null,
    // Multi-select for bulk actions
    val selectedOutfitIds: Set<String> = emptySet(),
    // Unified style composer
    val isComposerOpen: Boolean = false,
    /** Non-null when the composer is editing an existing saved style (update-in-place). */
    val composerEditingOutfitId: String? = null,
    val composerItemIds: List<String> = emptyList(),
    val composerWeatherMode: ComposerWeatherMode = ComposerWeatherMode.AUTO,
    val composerManualSeason: String = "",        // "" / Spring / Summer / Fall / Winter
    val composerManualTempC: Int? = null,          // null = unspecified
    val composerManualPrecip: String = "",         // "" / None / Light / Heavy
    val composerVibes: Set<String> = emptySet(),   // Casual / Sporty / Formal / …
    val composerName: String = "",
    val composerDescription: String = "",
    val composerTags: List<String> = emptyList(),
    val composerFeedback: String = "",
    val composerFeedbackHistory: List<String> = emptyList(),
    val composerReason: String = "",
    /**
     * Folder IDs of the closets to source items from when adding/suggesting/composing in the
     * composer. Empty set = unrestricted (sources from every closet). Defaults to the user's
     * default closet on open; the user can toggle other closets in the composer.
     */
    val composerSourceFolderIds: Set<String> = emptySet(),
    val isComposerEnhancing: Boolean = false,
    val composerError: String? = null,
    val composerSlots: List<OutfitSlot> = emptyList(),
    /** Snapshot of [composerSlots] selected-item ids at composer open. Used to detect real changes. */
    val composerInitialItemIds: List<String?> = emptyList(),
    val composerMode: ComposerMode = ComposerMode.EDIT,
    val composerAiSuggestedName: String = "",
    val composerAiSuggestedDescription: String = "",
    val composerAiSuggestedTags: List<String> = emptyList(),
    /**
     * AI-generated outfit variants the user can swipe through. Empty until the composer's
     * "Generate with AI" flow returns; cleared on close/discard or when re-running the AI.
     */
    val composerSuggestions: List<ComposerSuggestion> = emptyList(),
    val composerSuggestionIndex: Int = 0,
    /** True while the fullscreen swipe-through preview of AI suggestions is shown. */
    val composerSuggestionsViewerOpen: Boolean = false,
    val isSaveDialogOpen: Boolean = false,
    val predictionSetupSource: PredictionSetupSource = PredictionSetupSource.OUTFITS_LIST,
    /** True while the "Find with AI" setup dialog is showing on the Outfits tab. */
    val isPredictionSetupOpen: Boolean = false,
    /**
     * ISO date ("YYYY-MM-DD") of the future forecast day the user picked for the Find/Create
     * with AI setup. null means use today's live weather (default).
     */
    val composerForecastDate: String? = null,
    /** Number of suggestions Gemini is asked for (1..10). Default 3. */
    val composerSuggestionCount: Int = 3,
    /**
     * Per-invocation override of the AI considerations toggles (Settings → AI → standard
     * criteria). Null means "use the user's saved settings as-is"; set as soon as the user
     * touches one of the chips in the Find/Create-with-AI dialog so subsequent toggles
     * mutate the override instead of reverting to settings on every render.
     */
    val composerConsiderationsOverride: AiConsiderations? = null,
    val error: String? = null,
    /** AI tag-suggestion flow launched from the outfit detail viewer. */
    val tagSuggestion: TagSuggestionState? = null,
    /** Outfit currently being tag-edited from the detail viewer (chips clicked). */
    val tagEditingOutfitId: String? = null,
    /**
     * Set when the composer is invoked from a trip-day card. Drives header rendering,
     * weather pre-seed, and AI prompt augmentation. Cleared on [closeComposer].
     */
    val composerTripContext: TripContext? = null,
)

/** Transient state for the "Suggest tags" action in the outfit detail viewer. */
data class TagSuggestionState(
    val outfitId: String,
    val isLoading: Boolean = true,
    val suggestions: List<String> = emptyList(),
    val error: String? = null,
    val isSaving: Boolean = false,
)

