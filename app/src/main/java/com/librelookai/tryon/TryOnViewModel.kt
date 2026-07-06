package com.librelookai.tryon
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import com.librelookai.data.drive.DriveService
import com.librelookai.data.model.TryOn
import com.librelookai.gemini.AiClient
import com.librelookai.gemini.AiResult
import com.librelookai.gemini.AiRetry
import com.librelookai.gemini.getOrNull
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
    // There are no open/layer flags any more (§ 5 slice 9): the composer, history feed and
    // history detail are real navigation destinations (TryOnRoute / TryOnHistoryRoute /
    // TryOnDetailRoute) — this state is the composer draft + the derived history.
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

    /**
     * Pre-formatted error message (e.g. a raw exception message). Prefer [errorRes] for
     * localized copy — strings resolved in the ViewModel use the Application context's
     * *system* locale, which ignores the user's in-app language override. UI-layer
     * `stringResource(errorRes)` resolves against the localized `LocalContext` instead.
     */
    val error: String? = null,
    /** String resource for the error dialog body, resolved in the composable (app locale). */
    val errorRes: Int? = null,
)

/**
 * The **composer** half of the try-on VM split (refactor § 5 slice 9): assemble a composition,
 * generate, preview, save. History (feed + detail) lives on [TryOnHistoryViewModel]; both go
 * through [TryOnRepository] so the two VMs never call each other.
 *
 * Flow:
 *  1. [openComposer] seeds with wardrobe item Drive IDs; the UI shows a composer where the
 *     user can add/remove items.
 *  2. [generate] sends the current items + the user's reference photos to Gemini.
 *  3. The generated PNG lives in the app cache; the user can zoom it, regenerate, or
 *     [saveCurrent] it to Drive (uploaded to the _tryons subfolder, indexed in _tryons.json —
 *     the repo's store write carries it into the derived history).
 */
@HiltViewModel
class TryOnViewModel @Inject constructor(
    app: Application,
    private val gemini: AiClient,
    private val aiRetry: AiRetry,
    private val drive: DriveService,
    private val repo: TryOnRepository,
) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(TryOnUiState())
    val state: StateFlow<TryOnUiState> = _state.asStateFlow()

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

    /** Seed the composer draft with an initial set of wardrobe items (callers navigate to
     *  [com.librelookai.TryOnRoute] themselves — § 5 slice 9). Loads history lazily. */
    fun openComposer(
        seedItemIds: Set<String>,
        sourceOutfitId: String? = null,
        sourceKind: TryOnSourceKind = if (sourceOutfitId != null) TryOnSourceKind.OUTFIT else TryOnSourceKind.NONE,
        sourceContext: String? = null,
        autoPick: Boolean = false,
    ) {
        _state.update {
            it.copy(
                itemIds = seedItemIds,
                sourceOutfitId = sourceOutfitId,
                sourceKind = sourceKind,
                sourceContext = sourceContext,
                autoPick = autoPick,
                resultPath = null,
                isResultSaved = false,
                error = null,
                errorRes = null,
            )
        }
    }

    /** Consume the one-shot [TryOnUiState.autoPick] flag once the composer has opened its picker. */
    fun consumeAutoPick() = _state.update { it.copy(autoPick = false) }

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

    /** Clears the composer draft (composition / result / errors); the route pops separately
     *  (§ 5 slice 9 — this is state hygiene, not navigation). */
    fun close() {
        _state.value = TryOnUiState()
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

    fun clearError() = _state.update { it.copy(error = null, errorRes = null) }

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
            _state.update { it.copy(error = null, errorRes = com.librelookai.R.string.tryon_missing_photos_items) }
            return
        }
        // Refuse unless the selection covers both upper and lower body (a full-body piece, or a
        // top + a bottom) so the generated image is never undressed.
        if (!selected.tryOnFullyCovered()) {
            _state.update { it.copy(error = null, errorRes = com.librelookai.R.string.tryon_coverage_blocked) }
            return
        }
        // Register a one-tap retry so the global AI-failure dialog can re-run this generation.
        aiRetry.action = { generate(personFiles, wardrobeImages, preferences) }
        viewModelScope.launch {
            _state.update {
                it.copy(isGenerating = true, error = null, errorRes = null, resultPath = null, isResultSaved = false)
            }
            val outDir = File(drive.cacheDir, "tryon_results").apply { mkdirs() }
            val files = itemFiles.map { it.second }
            val ids = itemFiles.map { it.first }
            val snapshotSourceOutfitId = _state.value.sourceOutfitId
            val snapshotSourceKind = _state.value.sourceKind
            val snapshotSourceContext = _state.value.sourceContext ?: ""
            val source = snapshotSourceKind.serialized()
            val outcome = gemini.tryOnOutfit(personFiles, files, outDir, preferences, notify = true)
            if (outcome is AiResult.InsufficientCredits) {
                // The global InsufficientCreditsDialog (installed in MainActivity)
                // surfaces the buy prompt; here we just reset the loading state.
                Analytics.event("tryon_generate", mapOf("result" to "failed", "reason" to "credits", "source" to source))
                _state.update { it.copy(isGenerating = false) }
                return@launch
            }
            val result = outcome.getOrNull()
            if (result == null) {
                Analytics.event("tryon_generate", mapOf("result" to "failed", "reason" to "no_response", "source" to source))
                _state.update {
                    it.copy(isGenerating = false, error = null, errorRes = com.librelookai.R.string.tryon_generate_failed)
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
            _state.update { it.copy(isSaving = true, error = null, errorRes = null) }
            try {
                val root = repo.ensureRootFolder()
                val ids = lastGeneratedItemIds
                val names = ids.mapNotNull { id -> wardrobeImages.firstOrNull { it.driveId == id }?.name }
                val file = File(path)
                val driveName = "tryon_${System.currentTimeMillis()}.png"
                val driveId = drive.uploadTryOnImage(root, driveName, file)
                // Cache the uploaded image under the Drive ID so future reloads pick it up.
                val cached = repo.cacheFile(driveId)
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
                // Store write + queued index sync (§ 2) — the derived history picks the entry
                // up via invalidation (the history VM's feed follows without a cross-VM call).
                // The merge base stays a *Drive* read: the store may still be pre-warm-empty
                // here, and the image upload above just proved we're online.
                repo.writeAll(listOf(entry) + repo.loadEntries())
                _state.update { it.copy(isSaving = false, isResultSaved = true) }
            } catch (e: Exception) {
                Log.e("TryOnVM", "saveCurrent failed", e)
                _state.update {
                    it.copy(
                        isSaving = false,
                        error = e.message,
                        errorRes = if (e.message == null) com.librelookai.R.string.error_save_failed else null,
                    )
                }
            }
        }
    }

}
