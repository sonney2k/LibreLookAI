package com.librelookai.tryon
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.librelookai.auth.GoogleAuthManager
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.loadTryOnsJson
import com.librelookai.data.drive.saveTryOnsJson
import com.librelookai.data.drive.uploadTryOnImage
import com.librelookai.data.model.TryOn
import com.librelookai.gemini.GeminiRepository
import com.librelookai.gemini.tryOnOutfit
import com.librelookai.util.Analytics
import com.librelookai.wardrobe.DriveImage

/** Where a try-on composition was started from. Drives the source banner + history provenance. */
enum class TryOnSourceKind { NONE, OUTFIT, WARDROBE, SHOPPING, TRAVEL }

/** Stable lowercase token persisted in `_tryons.json`. NONE collapses to "outfit" for compat. */
fun TryOnSourceKind.serialized(): String = when (this) {
    TryOnSourceKind.WARDROBE -> "wardrobe"
    TryOnSourceKind.SHOPPING -> "shopping"
    TryOnSourceKind.TRAVEL   -> "travel"
    else                     -> "outfit"
}

fun tryOnSourceKindOf(token: String?): TryOnSourceKind = when (token) {
    "wardrobe" -> TryOnSourceKind.WARDROBE
    "shopping" -> TryOnSourceKind.SHOPPING
    "travel"   -> TryOnSourceKind.TRAVEL
    else       -> TryOnSourceKind.OUTFIT
}

data class TryOnUiState(
    /** Unified composer: open when the user is assembling / generating / previewing a try-on. */
    val isComposerOpen: Boolean = false,
    /** Drive IDs of the wardrobe items currently included in the composition. */
    val itemIds: Set<String> = emptySet(),
    /** Where the current composition originated — renders the composer's source banner. */
    val sourceKind: TryOnSourceKind = TryOnSourceKind.NONE,
    /** Display label backing the source banner (outfit name / "{n} items" / shopping item). */
    val sourceContext: String? = null,
    /**
     * When true, the composer auto-opens the picker matching [sourceKind] on first
     * composition (outfit picker for OUTFIT, item picker for WARDROBE / SHOPPING). Cleared
     * once consumed so a recomposition doesn't reopen it.
     */
    val autoPick: Boolean = false,
    /**
     * Id of the saved [com.librelookai.data.model.Outfit] backing the current composition
     * (set when the user picks an outfit via the composer's "Use an outfit" button). Cleared
     * the moment the user manually edits the item list so we don't persist a stale link.
     */
    val sourceOutfitId: String? = null,

    val isGenerating: Boolean = false,
    /** Local absolute path of the most recent generated image (unsaved or saved). */
    val resultPath: String? = null,
    /** True once the current [resultPath] has been persisted to Drive. */
    val isResultSaved: Boolean = false,
    val isSaving: Boolean = false,

    /** Past try-ons loaded from Drive, newest-first. */
    val history: List<TryOn> = emptyList(),
    val isHistoryOpen: Boolean = false,
    /**
     * True when the history feed itself is the dialog root (opened from the Quick sheet's
     * "See past try-ons" shortcut or the history FAB). Back from the feed then closes the
     * whole dialog instead of falling through to an empty composer.
     */
    val historyIsRoot: Boolean = false,
    /** Non-null when the user is viewing a past try-on from history. */
    val viewingTryOn: TryOn? = null,
    /**
     * True when the detail view was opened directly as the dialog root (e.g. from the
     * Outfits → Try-Ons sub-tab). In that case the back/close action should close the
     * whole dialog rather than fall back to the in-dialog history grid.
     */
    val historyDetailIsRoot: Boolean = false,

    val error: String? = null,
)

/**
 * Unified Try-on orchestration.
 *
 * Flow:
 *  1. [openComposer] seeds with wardrobe item Drive IDs; the UI shows a composer where the
 *     user can add/remove items.
 *  2. [generate] sends the current items + the user's reference photos to Gemini.
 *  3. The generated PNG lives in the app cache; the user can zoom it, regenerate, or
 *     [saveCurrent] it to Drive (uploaded to the _tryons subfolder, indexed in _tryons.json).
 *  4. [openHistory] surfaces the user's past try-ons; [viewTryOn] opens one.
 */
class TryOnViewModel(app: Application) : AndroidViewModel(app) {

    private val gemini = GeminiRepository(app)
    private val drive = DriveRepository(app, GoogleAuthManager(app))
    private val gson = Gson()

    private val _state = MutableStateFlow(TryOnUiState())
    val state: StateFlow<TryOnUiState> = _state.asStateFlow()

    private var rootFolderId: String? = null
    /**
     * Files that were actually sent to Gemini for the current [resultPath], in the same order.
     * Captured so that Save writes the correct itemNames even if the user edits the item
     * selection after generation.
     */
    private var lastGeneratedItemFiles: List<File> = emptyList()
    private var lastGeneratedItemIds: List<String> = emptyList()
    /** Outfit-id snapshot from when [generate] ran — written into the saved entry by [saveCurrent]. */
    private var lastGeneratedSourceOutfitId: String? = null
    /** Source kind/context snapshot from [generate], written into the saved entry by [saveCurrent]. */
    private var lastGeneratedSourceKind: TryOnSourceKind = TryOnSourceKind.NONE
    private var lastGeneratedSourceContext: String = ""

    /** Open the composer with an initial set of wardrobe items. Loads history lazily. */
    fun openComposer(
        seedItemIds: Set<String>,
        sourceOutfitId: String? = null,
        sourceKind: TryOnSourceKind = if (sourceOutfitId != null) TryOnSourceKind.OUTFIT else TryOnSourceKind.NONE,
        sourceContext: String? = null,
        autoPick: Boolean = false,
    ) {
        _state.update {
            it.copy(
                isComposerOpen = true,
                itemIds = seedItemIds,
                sourceOutfitId = sourceOutfitId,
                sourceKind = sourceKind,
                sourceContext = sourceContext,
                autoPick = autoPick,
                resultPath = null,
                isResultSaved = false,
                isHistoryOpen = false,
                viewingTryOn = null,
                error = null,
            )
        }
        loadHistory()
    }

    /** Consume the one-shot [TryOnUiState.autoPick] flag once the composer has opened its picker. */
    fun consumeAutoPick() = _state.update { it.copy(autoPick = false) }

    /** Reopen the Quick sheet flow from the composer's Swap button by clearing the composer. */
    fun requestSwapSource() = _state.update {
        it.copy(isComposerOpen = false, autoPick = false)
    }

    /**
     * Replace the current composition with the items from [outfit] and remember the link so
     * the saved try-on can jump back to it later. Any earlier item-by-item picks are dropped.
     */
    fun selectOutfit(outfit: com.librelookai.data.model.Outfit) {
        _state.update {
            it.copy(
                itemIds = outfit.itemIds.toSet(),
                sourceOutfitId = outfit.id,
                sourceKind = TryOnSourceKind.OUTFIT,
                sourceContext = outfit.name,
            )
        }
    }

    /**
     * Open the composer dialog directly into history-detail view for [tryOn].
     * Used by the Outfits → Try-Ons sub-tab: tapping a tile lands on the detail
     * page; dismissing detail returns to the in-dialog history grid; closing
     * the dialog returns the user to the sub-tab.
     */
    fun openHistoryDetail(tryOn: TryOn) {
        _state.update {
            it.copy(
                isComposerOpen = true,
                isHistoryOpen  = true,
                viewingTryOn   = tryOn,
                historyDetailIsRoot = true,
                itemIds        = emptySet(),
                resultPath     = null,
                isResultSaved  = false,
                error          = null,
            )
        }
    }

    fun close() {
        _state.value = TryOnUiState(history = _state.value.history)
    }

    // Manual edits clear sourceOutfitId — once the user diverges from the picked outfit,
    // the link no longer represents what's about to be tried on.
    // Diverging from a picked outfit drops the outfit link and demotes the banner to a generic
    // wardrobe source (unless we started from shopping, which we keep so provenance survives).
    private fun demoteSourceOnEdit(kind: TryOnSourceKind): TryOnSourceKind =
        if (kind == TryOnSourceKind.OUTFIT || kind == TryOnSourceKind.NONE) TryOnSourceKind.WARDROBE else kind

    fun addItem(driveId: String) = _state.update {
        it.copy(itemIds = it.itemIds + driveId, sourceOutfitId = null, sourceKind = demoteSourceOnEdit(it.sourceKind))
    }
    fun removeItem(driveId: String) = _state.update {
        it.copy(itemIds = it.itemIds - driveId, sourceOutfitId = null, sourceKind = demoteSourceOnEdit(it.sourceKind))
    }
    fun setItems(ids: Set<String>) = _state.update {
        it.copy(itemIds = ids, sourceOutfitId = null, sourceKind = demoteSourceOnEdit(it.sourceKind))
    }

    fun openHistory() = _state.update { it.copy(isHistoryOpen = true, viewingTryOn = null, historyDetailIsRoot = false) }

    /** Open the dialog straight into the history hero feed (Quick sheet shortcut / FAB). */
    fun openHistoryRoot() {
        _state.update {
            it.copy(
                isComposerOpen = true,
                isHistoryOpen = true,
                historyIsRoot = true,
                historyDetailIsRoot = false,
                viewingTryOn = null,
                resultPath = null,
                isResultSaved = false,
                autoPick = false,
                error = null,
            )
        }
        loadHistory()
    }

    fun closeHistory() = _state.update { it.copy(isHistoryOpen = false, historyIsRoot = false, viewingTryOn = null, historyDetailIsRoot = false) }
    fun viewTryOn(t: TryOn) = _state.update { it.copy(viewingTryOn = t, historyDetailIsRoot = false) }
    fun dismissViewingTryOn() = _state.update { it.copy(viewingTryOn = null, historyDetailIsRoot = false) }

    fun clearError() = _state.update { it.copy(error = null) }

    /**
     * Generate a try-on using the currently selected items and the user's reference photos.
     * The result is only cached locally; it is persisted to Drive via [saveCurrent].
     */
    fun generate(personFiles: List<File>, wardrobeImages: List<DriveImage>, preferences: String) {
        val selected = wardrobeImages.filter { it.driveId in _state.value.itemIds }
        val itemFiles = selected
            .map { it.driveId to File(it.localPath) }
            .filter { (_, f) -> f.exists() }
        if (personFiles.isEmpty() || itemFiles.isEmpty()) {
            _state.update {
                it.copy(
                    error = getApplication<Application>()
                        .getString(com.librelookai.R.string.tryon_missing_photos_items),
                )
            }
            return
        }
        // Refuse unless the selection covers both upper and lower body (a full-body piece, or a
        // top + a bottom) so the generated image is never undressed.
        if (!selected.tryOnFullyCovered()) {
            _state.update {
                it.copy(
                    error = getApplication<Application>()
                        .getString(com.librelookai.R.string.tryon_coverage_blocked),
                )
            }
            return
        }
        // Register a one-tap retry so the global AI-failure dialog can re-run this generation.
        com.librelookai.gemini.AiRetry.action = { generate(personFiles, wardrobeImages, preferences) }
        viewModelScope.launch {
            _state.update {
                it.copy(isGenerating = true, error = null, resultPath = null, isResultSaved = false)
            }
            val outDir = File(drive.cacheDir, "tryon_results").apply { mkdirs() }
            val files = itemFiles.map { it.second }
            val ids = itemFiles.map { it.first }
            val snapshotSourceOutfitId = _state.value.sourceOutfitId
            val snapshotSourceKind = _state.value.sourceKind
            val snapshotSourceContext = _state.value.sourceContext ?: ""
            val source = snapshotSourceKind.serialized()
            val result = try {
                gemini.tryOnOutfit(personFiles, files, outDir, preferences, notify = true)
            } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                // The global InsufficientCreditsDialog (installed in MainActivity)
                // surfaces the buy prompt; here we just reset the loading state.
                Analytics.event("tryon_generate", mapOf("result" to "failed", "reason" to "credits", "source" to source))
                _state.update { it.copy(isGenerating = false) }
                return@launch
            }
            if (result == null) {
                Analytics.event("tryon_generate", mapOf("result" to "failed", "reason" to "no_response", "source" to source))
                _state.update {
                    it.copy(
                        isGenerating = false,
                        error = getApplication<Application>()
                            .getString(com.librelookai.R.string.tryon_generate_failed),
                    )
                }
            } else {
                Analytics.event("tryon_generate", mapOf("result" to "ok", "source" to source, "items" to files.size.toString()))
                lastGeneratedItemFiles = files
                lastGeneratedItemIds = ids
                lastGeneratedSourceOutfitId = snapshotSourceOutfitId
                lastGeneratedSourceKind = snapshotSourceKind
                lastGeneratedSourceContext = snapshotSourceContext
                _state.update {
                    it.copy(isGenerating = false, resultPath = result.absolutePath, isResultSaved = false)
                }
            }
        }
    }

    /** Uploads the current [resultPath] to Drive and records it in [_tryons.json]. */
    fun saveCurrent(wardrobeImages: List<DriveImage>) {
        val path = _state.value.resultPath ?: return
        if (_state.value.isResultSaved || _state.value.isSaving) return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            try {
                val root = ensureRootFolder()
                val ids = lastGeneratedItemIds
                val names = ids.mapNotNull { id -> wardrobeImages.firstOrNull { it.driveId == id }?.name }
                val file = File(path)
                val driveName = "tryon_${System.currentTimeMillis()}.png"
                val driveId = drive.uploadTryOnImage(root, driveName, file)
                // Cache the uploaded image under the Drive ID so future reloads pick it up.
                val cached = File(drive.cacheDir, "tryon_$driveId.png")
                runCatching { file.copyTo(cached, overwrite = true) }

                val entry = TryOn(
                    imageDriveId   = driveId,
                    imageName      = driveName,
                    itemNames      = names,
                    createdAt      = System.currentTimeMillis(),
                    sourceOutfitId = lastGeneratedSourceOutfitId,
                    sourceKind     = lastGeneratedSourceKind.serialized(),
                    sourceContext  = lastGeneratedSourceContext,
                    itemIds        = ids,
                    localPath      = cached.absolutePath,
                )
                val existing = loadTryOnsEntries(root)
                val updated = listOf(entry) + existing
                drive.saveTryOnsJson(root, gson.toJson(updated))
                _state.update {
                    it.copy(
                        isSaving      = false,
                        isResultSaved = true,
                        history       = listOf(entry) + it.history,
                    )
                }
            } catch (e: Exception) {
                Log.e("TryOnVM", "saveCurrent failed", e)
                _state.update {
                    it.copy(
                        isSaving = false,
                        error = e.message ?: getApplication<Application>()
                            .getString(com.librelookai.R.string.error_save_failed),
                    )
                }
            }
        }
    }

    fun deleteTryOn(tryOn: TryOn) {
        viewModelScope.launch {
            try {
                val root = ensureRootFolder()
                runCatching { drive.deleteFile(tryOn.imageDriveId) }
                File(drive.cacheDir, "tryon_${tryOn.imageDriveId}.png").delete()
                val remaining = loadTryOnsEntries(root).filterNot { it.imageDriveId == tryOn.imageDriveId }
                drive.saveTryOnsJson(root, gson.toJson(remaining))
                _state.update {
                    it.copy(
                        history        = it.history.filterNot { h -> h.imageDriveId == tryOn.imageDriveId },
                        viewingTryOn   = if (it.viewingTryOn?.imageDriveId == tryOn.imageDriveId) null else it.viewingTryOn,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        error = e.message ?: getApplication<Application>()
                            .getString(com.librelookai.R.string.error_delete_failed),
                    )
                }
            }
        }
    }

    /**
     * Batch-deletes [tryOns] in a single Drive JSON write. Used when wardrobe items are deleted
     * and the user opts to cascade the removal to try-ons that wore those items.
     */
    fun deleteTryOns(tryOns: List<TryOn>) {
        if (tryOns.isEmpty()) return
        val ids = tryOns.map { it.imageDriveId }.toSet()
        viewModelScope.launch {
            try {
                val root = ensureRootFolder()
                tryOns.forEach { t ->
                    runCatching { drive.deleteFile(t.imageDriveId) }
                    File(drive.cacheDir, "tryon_${t.imageDriveId}.png").delete()
                }
                val remaining = loadTryOnsEntries(root).filterNot { it.imageDriveId in ids }
                drive.saveTryOnsJson(root, gson.toJson(remaining))
                runCatching { historyCacheFile().writeText(gson.toJson(remaining)) }
                _state.update {
                    it.copy(
                        history = it.history.filterNot { h -> h.imageDriveId in ids },
                        viewingTryOn = if (it.viewingTryOn?.imageDriveId in ids) null else it.viewingTryOn,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        error = e.message ?: getApplication<Application>()
                            .getString(com.librelookai.R.string.error_delete_failed),
                    )
                }
            }
        }
    }

    fun loadHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            // Phase 1 — instant: paint from the local sidecar cache so the history grid
            // appears immediately when the user opens the Try-Ons tab. Mirrors the
            // wardrobe two-phase load (CLAUDE.md → Storage / Wardrobe).
            runCatching {
                val cacheFile = historyCacheFile()
                if (cacheFile.exists()) {
                    val cached: List<TryOn> = gson.fromJson(
                        cacheFile.readText(),
                        object : TypeToken<List<TryOn>>() {}.type,
                    ) ?: emptyList()
                    val resolved = cached.mapNotNull { e ->
                        val f = File(drive.cacheDir, "tryon_${e.imageDriveId}.png")
                        if (f.exists()) e.copy(localPath = f.absolutePath) else null
                    }
                    if (resolved.isNotEmpty()) {
                        _state.update { it.copy(history = resolved) }
                    }
                }
            }

            // Phase 2 — refresh from Drive.
            try {
                val root = ensureRootFolder()
                val entries = loadTryOnsEntries(root)
                val resolved = entries.map { e ->
                    val cached = File(drive.cacheDir, "tryon_${e.imageDriveId}.png")
                    if (!cached.exists()) {
                        runCatching { drive.downloadFileTo(e.imageDriveId, cached) }
                    }
                    e.copy(localPath = cached.absolutePath.takeIf { cached.exists() } ?: "")
                }
                _state.update { it.copy(history = resolved) }
                runCatching { historyCacheFile().writeText(gson.toJson(entries)) }
            } catch (e: Exception) {
                Log.w("TryOnVM", "loadHistory failed: ${e.message}")
            }
        }
    }

    private fun historyCacheFile(): File =
        File(getApplication<Application>().filesDir, "tryons_cache.json")

    private suspend fun ensureRootFolder(): String {
        rootFolderId?.let { return it }
        return withContext(Dispatchers.IO) { drive.getOrCreateFolder() }.also { rootFolderId = it }
    }

    private suspend fun loadTryOnsEntries(rootFolderId: String): List<TryOn> {
        val json = drive.loadTryOnsJson(rootFolderId) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<TryOn>>(json, object : TypeToken<List<TryOn>>() {}.type) ?: emptyList()
        }.getOrElse { emptyList() }.map(::migrateSource)
    }

    // Old entries predate sourceKind: Gson fills the default "outfit", but an item-by-item
    // try-on (no sourceOutfitId) was really a wardrobe source. Infer it so provenance reads right.
    private fun migrateSource(t: TryOn): TryOn =
        if (t.sourceOutfitId == null && t.sourceKind == "outfit")
            t.copy(sourceKind = "wardrobe")
        else t
}
