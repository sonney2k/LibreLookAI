package com.librelookai.outfit

import android.app.Application
import android.content.Context
import android.telephony.TelephonyManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.librelookai.data.drive.DriveService
import com.librelookai.data.model.Outfit
import com.librelookai.data.session.ClosetSessionHolder
import com.librelookai.gemini.GeminiRepository
import com.librelookai.settings.UserPreferences
import com.librelookai.util.Analytics
import com.librelookai.wardrobe.DriveImage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The AI **generation** side of outfits (refactor § 5 slice 9 — the 2-way split of the old
 * `OutfitsViewModel`): owns the unified composer and the "Find with AI" prediction, which share
 * one state machine (both read and write the same `composer*` generation-parameter cluster).
 * The list surface stays on [OutfitsViewModel]; shared data + the save funnel live on
 * [OutfitsRepository], so the two VMs never call each other — saves go through
 * [OutfitsRepository.saveOutfit] / [OutfitsRepository.persistOutfitFolders], and the
 * save→scroll / save→wear-offer hand-offs ride the repo's one-shot event / pending-wear flow.
 *
 * The composer / prediction / tag-suggestion workflows stay same-package extension functions
 * (`OutfitsComposer.kt` / `OutfitsPrediction.kt` / `OutfitsTags.kt`) retargeted at this VM.
 */
@HiltViewModel
class OutfitGenerationViewModel @Inject constructor(
    app: Application,
    internal val drive: DriveService,
    internal val gemini: GeminiRepository,
    private val outfitsRepo: OutfitsRepository,
    session: ClosetSessionHolder,
    eventStore: com.librelookai.data.local.OutfitEventStore,
) : AndroidViewModel(app) {
    /** Weekly, location-specific cache around the expensive Gemini trend lookup. */
    internal val trendsCache = com.librelookai.gemini.FashionTrendsCache(app, drive, gemini)
    internal val gson = Gson()
    /** Load scope + save target live on [outfitsRepo]; exposed read-only for the extensions. */
    internal val folderId: String? get() = outfitsRepo.folderId
    internal val saveFolderId: String? get() = outfitsRepo.saveFolderId

    internal val _state = MutableStateFlow(OutfitGenerationUiState())
    val state: StateFlow<OutfitGenerationUiState> = _state.asStateFlow()

    init {
        // Mirror the repo's derived outfits + the store-derived wear history into the generation
        // state: the prompts read them as taste signals, the save paths for edit-lookup. The
        // list VM keeps its own mirrors for the list surface — the repo is the single source.
        viewModelScope.launch {
            outfitsRepo.outfits.collect { outfits -> _state.update { it.copy(outfits = outfits) } }
        }
        viewModelScope.launch {
            wearHistoryFlow(session, eventStore).collect { events ->
                _state.update { it.copy(wearHistory = events) }
            }
        }
    }

    /** Thin delegator to [OutfitsRepository.persistOutfitFolders] for the extensions. */
    internal suspend fun persistOutfitFolders(styles: List<Outfit>, affected: Collection<String>) =
        outfitsRepo.persistOutfitFolders(styles, affected)

    /** Opens the unified composer for an existing saved style (update-in-place on save). */
    fun startEditing(
        style: Outfit,
        images: List<DriveImage>,
        prefs: UserPreferences?,
        tripContext: TripContext? = null,
    ) {
        openComposer(
            seedItemIds        = style.itemIds.toSet(),
            images             = images,
            prefs              = prefs,
            initialName        = style.name,
            initialDescription = style.description,
            editingStyleId     = style.id,
            tripContext        = tripContext,
        )
    }

    // ---------- Save ----------

    fun prepareSave() = _state.update { s ->
        val existingOutfit = s.composerEditingOutfitId?.let { id -> s.outfits.find { it.id == id } }
        s.copy(
            isSaveDialogOpen = true,
            composerName = s.composerName.ifBlank { existingOutfit?.name ?: s.composerAiSuggestedName },
            composerDescription = s.composerDescription.ifBlank { existingOutfit?.description ?: s.composerAiSuggestedDescription },
            composerTags = s.composerTags.ifEmpty { existingOutfit?.tags ?: s.composerAiSuggestedTags },
        )
    }

    fun dismissSaveDialog() = _state.update { it.copy(isSaveDialogOpen = false) }

    fun commitOutfit(name: String, description: String, tags: List<String>, onDone: (Boolean) -> Unit = {}) {
        val s = _state.value
        val itemIds = s.composerSlots.mapNotNull { it.selectedItemId }.distinct()
            .ifEmpty { s.composerItemIds }
        if (itemIds.isEmpty()) { onDone(false); return }
        val editingId = s.composerEditingOutfitId
        // The Drive write is a network round-trip; surface a "saving" overlay so the user isn't
        // left staring at the unchanged composer until the list silently reappears.
        _state.update { it.copy(isComposerSaving = true) }
        // Funnel terminal helper: whether the saved outfit used AI suggestions, plus create/edit + size.
        val aiGenerated = s.composerSuggestions.isNotEmpty()
        fun logOutfitSaved(mode: String) = Analytics.event(
            "outfit_saved",
            mapOf("mode" to mode, "ai_generated" to aiGenerated.toString(), "items" to itemIds.size.toString()),
        )
        if (editingId == null) {
            viewModelScope.launch {
                val saved = outfitsRepo.saveOutfit(
                    name        = name.ifBlank { "Outfit ${s.outfits.size + 1}" },
                    description = description,
                    itemIds     = itemIds,
                    tags        = tags,
                )
                if (saved != null) { logOutfitSaved("create"); closeComposer() }
                else _state.update { it.copy(isComposerSaving = false) }
                onDone(saved != null)
            }
            return
        }
        val existing = s.outfits.find { it.id == editingId } ?: run { onDone(false); return }
        val resolvedName = name.ifBlank { existing.name }
        viewModelScope.launch {
            val saveId = existing.folderId.ifEmpty { saveFolderId ?: folderId ?: run { onDone(false); return@launch } }
            val idToName = drive.listFiles(saveId).associate { it.id to it.name }
            val itemNames = itemIds.mapNotNull { idToName[it] }
            val edited = existing.copy(
                name = resolvedName,
                description = description,
                itemIds = itemIds,
                itemNames = itemNames,
                tags = tags,
                // Home the edit in the folder it is written to (see OutfitsRepository.saveOutfit).
                folderId = saveId,
            )
            val updated = s.outfits.map { if (it.id == edited.id) edited else it }
            // Offer "wear it now" on the list surface once the composer closes.
            outfitsRepo.setPendingWearOutfit(edited.id)
            persistOutfitFolders(updated, listOf(saveId))
            logOutfitSaved("edit")
            closeComposer()
            onDone(true)
        }
    }

    // ---------- Helpers the extensions share ----------

    internal fun deviceCountryCode(): String {
        val tel = getApplication<Application>()
            .getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return tel?.networkCountryIso?.uppercase()?.takeIf { it.isNotEmpty() }
            ?: tel?.simCountryIso?.uppercase()?.takeIf { it.isNotEmpty() }
            ?: Locale.getDefault().country.takeIf { it.isNotEmpty() }
            ?: "US"
    }
}
