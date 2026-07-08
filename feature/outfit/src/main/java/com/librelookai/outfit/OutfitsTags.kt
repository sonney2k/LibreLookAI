package com.librelookai.outfit
import com.librelookai.util.localized

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.gson.reflect.TypeToken
import com.librelookai.data.model.Outfit
import com.librelookai.gemini.AiResult
import com.librelookai.gemini.getOrNull
import com.librelookai.gemini.UsageCategory
import com.librelookai.settings.AppLanguage
import com.librelookai.settings.UserPreferences
import com.librelookai.wardrobe.DriveImage
import java.util.Locale
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun OutfitGenerationViewModel.suggestTagsForOutfit(outfit: Outfit, images: List<DriveImage>, prefs: UserPreferences?) {
        _state.update { it.copy(tagSuggestion = TagSuggestionState(outfitId = outfit.id, isLoading = true)) }
        viewModelScope.launch {
            val itemsById = images.associateBy { it.driveId }
            val items = outfit.itemIds.mapNotNull { itemsById[it] }
            val itemDescriptors = items.map { img ->
                val t = img.tags
                val parts = listOfNotNull(
                    t?.category?.takeIf { it.isNotBlank() }?.let { "category=$it" },
                    t?.type?.takeIf { it.isNotBlank() }?.let { "type=$it" },
                    t?.label?.takeIf { it.isNotBlank() }?.let { "label=$it" },
                    t?.colors?.takeIf { it.isNotEmpty() }?.let { "colors=${it.joinToString("/")}" },
                    t?.aesthetic?.takeIf { it.isNotEmpty() }?.let { "aesthetic=${it.joinToString("/")}" },
                    t?.seasonality?.takeIf { it.isNotEmpty() }?.let { "season=${it.joinToString("/")}" },
                    t?.uses?.takeIf { it.isNotEmpty() }?.let { "uses=${it.joinToString("/")}" },
                    t?.fit?.takeIf { it.isNotEmpty() }?.let { "fit=${it.joinToString("/")}" },
                    t?.material?.takeIf { it.isNotEmpty() }?.let { "material=${it.joinToString("/")}" },
                    t?.pattern?.takeIf { it.isNotEmpty() }?.let { "pattern=${it.joinToString("/")}" },
                )
                "- ${parts.joinToString(", ").ifBlank { img.name }}"
            }
            val langName = AppLanguage.toGeminiName(prefs?.language ?: AppLanguage.ENGLISH)
            val prompt = buildString {
                appendLine("You are tagging a clothing outfit.")
                appendLine("Suggest 3-6 short, lowercase, single-or-two-word tags describing occasions, vibes, seasons, settings, or dress codes that fit this outfit.")
                appendLine("Avoid restating individual garment categories (e.g. 'shirt', 'pants'). Avoid color-only tags unless the palette is the defining trait.")
                appendLine("Return ONLY a JSON array of strings, no markdown, no commentary. Example: [\"smart casual\",\"office\",\"autumn\"]")
                appendLine("Write tags in $langName.")
                appendLine()
                appendLine("Outfit name: ${outfit.name.ifBlank { "(untitled)" }}")
                if (outfit.description.isNotBlank()) appendLine("Description: ${outfit.description}")
                if (outfit.tags.isNotEmpty()) appendLine("Existing tags (do not repeat): ${outfit.tags.joinToString(", ")}")
                appendLine("Items:")
                itemDescriptors.forEach { appendLine(it) }
            }
            Log.d("StylesVM", "Suggest tags prompt:\n$prompt")
            val outcome = gemini.generateText(prompt, UsageCategory.OTHER)
            if (outcome is AiResult.InsufficientCredits) {
                _state.update {
                    it.copy(tagSuggestion = it.tagSuggestion?.copy(isLoading = false))
                }
                return@launch
            }
            val raw = outcome.getOrNull()
            if (raw == null) {
                _state.update {
                    it.copy(tagSuggestion = it.tagSuggestion?.copy(isLoading = false, error = getApplication<android.app.Application>().localized().getString(com.librelookai.core.designsystem.R.string.error_gemini_no_response)))
                }
                return@launch
            }
            val json = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val parsed: List<String> = runCatching {
                gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type)
            }.getOrElse {
                // Tolerate {"tags": [...]} shape.
                runCatching {
                    data class TagsResp(val tags: List<String> = emptyList())
                    gson.fromJson(json, TagsResp::class.java).tags
                }.getOrDefault(emptyList())
            } ?: emptyList()
            val existingLower = outfit.tags.map { it.lowercase(Locale.ROOT) }.toSet()
            val cleaned = parsed
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.lowercase(Locale.ROOT) !in existingLower }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .take(8)
            _state.update {
                it.copy(
                    tagSuggestion = it.tagSuggestion?.copy(
                        isLoading = false,
                        suggestions = cleaned,
                        error = if (cleaned.isEmpty()) "No new tags suggested." else null,
                    ),
                )
            }
        }
    }

fun OutfitGenerationViewModel.dismissTagSuggestions() = _state.update { it.copy(tagSuggestion = null) }

fun OutfitGenerationViewModel.openOutfitTagsEditor(outfitId: String) = _state.update { it.copy(tagEditingOutfitId = outfitId) }
fun OutfitGenerationViewModel.closeOutfitTagsEditor() = _state.update { it.copy(tagEditingOutfitId = null) }

    /** Replaces the tag list of an existing outfit and persists the change to Drive. */
fun OutfitGenerationViewModel.setOutfitTags(outfitId: String, newTags: List<String>) {
        val outfit = _state.value.outfits.find { it.id == outfitId } ?: return
        val cleaned = newTags
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
        if (cleaned == outfit.tags) {
            _state.update { it.copy(tagEditingOutfitId = null) }
            return
        }
        val updatedOutfit = outfit.copy(tags = cleaned)
        val targetFolderId = outfit.folderId.takeIf { it.isNotEmpty() } ?: saveFolderId ?: folderId ?: return
        val updatedAll = _state.value.outfits.map { if (it.id == outfitId) updatedOutfit else it }
        // Local-first: Room + the sync queue own the Drive write (refactor § 2); only the
        // outfit's home folder changed, so only that folder is enqueued. The derived outfits
        // list follows via invalidation (§ 5 slice 4b).
        _state.update { it.copy(tagEditingOutfitId = null) }
        viewModelScope.launch {
            persistOutfitFolders(updatedAll, listOf(targetFolderId))
        }
    }

    /** Merges [tagsToAdd] into the outfit's existing tags and persists the change to Drive. */
fun OutfitGenerationViewModel.applyTagSuggestions(outfitId: String, tagsToAdd: List<String>) {
        val outfit = _state.value.outfits.find { it.id == outfitId } ?: return
        if (tagsToAdd.isEmpty()) {
            _state.update { it.copy(tagSuggestion = null) }
            return
        }
        val existingLower = outfit.tags.map { it.lowercase(Locale.ROOT) }.toSet()
        val merged = outfit.tags + tagsToAdd
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.lowercase(Locale.ROOT) !in existingLower }
            .distinctBy { it.lowercase(Locale.ROOT) }
        if (merged.size == outfit.tags.size) {
            _state.update { it.copy(tagSuggestion = null) }
            return
        }
        val updatedOutfit = outfit.copy(tags = merged)
        val targetFolderId = outfit.folderId.takeIf { it.isNotEmpty() } ?: saveFolderId ?: folderId ?: return
        val updatedAll = _state.value.outfits.map { if (it.id == outfitId) updatedOutfit else it }
        // Local-first: the save commits to Room immediately and the Drive write rides the sync
        // queue, so there is no interim isSaving / failure state any more (see setOutfitTags).
        _state.update { it.copy(tagSuggestion = null) }
        viewModelScope.launch {
            persistOutfitFolders(updatedAll, listOf(targetFolderId))
        }
    }

    /**
     * Returns the ISO 3166-1 alpha-2 country code for the device's current location.
     * Prefers the mobile network registration country (accurate regardless of device language),
     * falls back to SIM country, then device locale as a last resort.
     */
