package com.librelookai

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

data class TryOnUiState(
    /** Unified composer: open when the user is assembling / generating / previewing a try-on. */
    val isComposerOpen: Boolean = false,
    /** Drive IDs of the wardrobe items currently included in the composition. */
    val itemIds: Set<String> = emptySet(),

    val isGenerating: Boolean = false,
    /** Local absolute path of the most recent generated image (unsaved or saved). */
    val resultPath: String? = null,
    /** True once the current [resultPath] has been persisted to Drive. */
    val isResultSaved: Boolean = false,
    val isSaving: Boolean = false,

    /** Past try-ons loaded from Drive, newest-first. */
    val history: List<TryOn> = emptyList(),
    val isHistoryOpen: Boolean = false,
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

    /** Open the composer with an initial set of wardrobe items. Loads history lazily. */
    fun openComposer(seedItemIds: Set<String>) {
        _state.update {
            it.copy(
                isComposerOpen = true,
                itemIds = seedItemIds,
                resultPath = null,
                isResultSaved = false,
                isHistoryOpen = false,
                viewingTryOn = null,
                error = null,
            )
        }
        loadHistory()
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

    fun addItem(driveId: String) = _state.update { it.copy(itemIds = it.itemIds + driveId) }
    fun removeItem(driveId: String) = _state.update { it.copy(itemIds = it.itemIds - driveId) }
    fun setItems(ids: Set<String>) = _state.update { it.copy(itemIds = ids) }

    fun openHistory() = _state.update { it.copy(isHistoryOpen = true, viewingTryOn = null, historyDetailIsRoot = false) }
    fun closeHistory() = _state.update { it.copy(isHistoryOpen = false, viewingTryOn = null, historyDetailIsRoot = false) }
    fun viewTryOn(t: TryOn) = _state.update { it.copy(viewingTryOn = t, historyDetailIsRoot = false) }
    fun dismissViewingTryOn() = _state.update { it.copy(viewingTryOn = null, historyDetailIsRoot = false) }

    fun clearError() = _state.update { it.copy(error = null) }

    /**
     * Generate a try-on using the currently selected items and the user's reference photos.
     * The result is only cached locally; it is persisted to Drive via [saveCurrent].
     */
    fun generate(personFiles: List<File>, wardrobeImages: List<DriveImage>, preferences: String) {
        val itemFiles = wardrobeImages
            .filter { it.driveId in _state.value.itemIds }
            .map { it.driveId to File(it.localPath) }
            .filter { (_, f) -> f.exists() }
        if (personFiles.isEmpty() || itemFiles.isEmpty()) {
            _state.update { it.copy(error = "Missing photos or items") }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(isGenerating = true, error = null, resultPath = null, isResultSaved = false)
            }
            val outDir = File(drive.cacheDir, "tryon_results").apply { mkdirs() }
            val files = itemFiles.map { it.second }
            val ids = itemFiles.map { it.first }
            val result = gemini.tryOnOutfit(personFiles, files, outDir, preferences)
            if (result == null) {
                _state.update { it.copy(isGenerating = false, error = "Generation failed") }
            } else {
                lastGeneratedItemFiles = files
                lastGeneratedItemIds = ids
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
                    imageDriveId = driveId,
                    imageName    = driveName,
                    itemNames    = names,
                    createdAt    = System.currentTimeMillis(),
                    itemIds      = ids,
                    localPath    = cached.absolutePath,
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
                _state.update { it.copy(isSaving = false, error = e.message ?: "Save failed") }
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
                _state.update { it.copy(error = e.message ?: "Delete failed") }
            }
        }
    }

    fun loadHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val root = ensureRootFolder()
                val entries = loadTryOnsEntries(root)
                // Resolve local paths — download missing PNGs in the background.
                val resolved = entries.map { e ->
                    val cached = File(drive.cacheDir, "tryon_${e.imageDriveId}.png")
                    if (!cached.exists()) {
                        runCatching { drive.downloadFileTo(e.imageDriveId, cached) }
                    }
                    e.copy(localPath = cached.absolutePath.takeIf { cached.exists() } ?: "")
                }
                _state.update { it.copy(history = resolved) }
            } catch (e: Exception) {
                Log.w("TryOnVM", "loadHistory failed: ${e.message}")
            }
        }
    }

    private suspend fun ensureRootFolder(): String {
        rootFolderId?.let { return it }
        return withContext(Dispatchers.IO) { drive.getOrCreateFolder() }.also { rootFolderId = it }
    }

    private suspend fun loadTryOnsEntries(rootFolderId: String): List<TryOn> {
        val json = drive.loadTryOnsJson(rootFolderId) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<TryOn>>(json, object : TypeToken<List<TryOn>>() {}.type) ?: emptyList()
        }.getOrElse { emptyList() }
    }
}
