package com.librelookai.wardrobe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import com.librelookai.R
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.DriveService
import com.librelookai.data.local.WardrobeItemStore
import com.librelookai.data.session.UserPreferencesRepository
import com.librelookai.gemini.ClothingTags
import com.librelookai.gemini.CutoutFixActions
import com.librelookai.gemini.AiClient
import com.librelookai.gemini.AiResult
import com.librelookai.gemini.getOrNull
import com.librelookai.gemini.fixCutoutBackground
import com.librelookai.service.JobLock
import com.librelookai.settings.AppLanguage
import com.librelookai.util.ImageEncoding
import com.librelookai.util.localized
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live progress of the single-item ops, mirrored into [WardrobeUiState] by the VM's
 * transitions-only collector — the same contract as [IngestionProgress]. The fields land in
 * the *shared* UI slots (`isProcessing`/`isUploading`/`processingImageId`), so pipeline and
 * item-op writes interleave last-writer-wins exactly like the pre-extraction in-VM writes.
 */
data class ItemOpProgress(
    val isProcessing: Boolean = false,
    val isUploading: Boolean = false,
    val processingImageId: String? = null,
)

/**
 * The per-item maintenance ops (refactor § 5 slice 9 VM slimming): background reprocess,
 * tag detection, tag writes, rotate and the single-item cutout fix, extracted verbatim from
 * `WardrobeViewModel`. Items are resolved from their [WardrobeItemStore] row (the source of
 * truth — works for any closet, not just the active view scope); byte changes under an
 * unchanged driveId bump [ItemVersions] so every derived view refreshes.
 *
 * Process-scoped (`@Singleton` + own scope) rather than ViewModel-scoped: an op started from
 * a destination-scoped viewer VM survives the destination popping, and its progress reaches
 * every VM instance mirroring [progress].
 */
@Singleton
class WardrobeItemOps @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val drive: DriveService,
    private val gemini: AiClient,
    private val itemStore: WardrobeItemStore,
    private val sidecarQueue: SidecarSyncQueue,
    private val itemVersions: ItemVersions,
    private val jobLock: JobLock,
    prefsRepo: UserPreferencesRepository,
) {
    companion object { private const val TAG = "WardrobeItemOps" }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _progress = MutableStateFlow(ItemOpProgress())
    val progress: StateFlow<ItemOpProgress> = _progress.asStateFlow()

    /** Error funnel for [WardrobeUiState.error]; a `null` emission clears it (op start). */
    private val _errors = MutableSharedFlow<String?>(extraBufferCapacity = 16)
    val errors: SharedFlow<String?> = _errors.asSharedFlow()

    /** Gemini-facing language name (e.g. "English", "German") for label generation. */
    private var geminiLanguage: String = "English"

    init {
        scope.launch {
            prefsRepo.preferences.collect { p -> geminiLanguage = AppLanguage.toGeminiName(p.language) }
        }
    }

    // ---------- Cache ----------

    /** Ensures the pre-cutout original for [cutoutDriveId] is cached at the canonical
     *  `${cutoutDriveId}_original.jpg` path and returns its absolute path, or null if no
     *  original exists or the download failed. */
    suspend fun ensureOriginalCached(cutoutDriveId: String): String? = withContext(Dispatchers.IO) {
        val origId = itemStore.find(cutoutDriveId)?.second?.originalDriveId ?: return@withContext null
        val local = File(drive.cacheDir, "${cutoutDriveId}_original.jpg")
        if (local.exists()) return@withContext local.absolutePath
        val downloaded = runCatching { drive.downloadToCache(origId) }.getOrNull() ?: return@withContext null
        runCatching { downloaded.copyTo(local, overwrite = true) }
        local.absolutePath
    }

    /**
     * Returns the best available original file for [driveId]:
     * 1. Local cache `${driveId}_original.jpg`
     * 2. Drive download via `originalDriveId` (cached locally for future use)
     * 3. The current cached (processed) image as last resort
     */
    private suspend fun resolveOriginalFile(driveId: String): File? {
        val localOriginal = File(drive.cacheDir, "${driveId}_original.jpg")
        if (localOriginal.exists()) return localOriginal

        val originalDriveId = itemStore.find(driveId)?.second?.originalDriveId
        if (originalDriveId != null) {
            val downloaded = drive.downloadToCache(originalDriveId)
            if (downloaded != null) {
                downloaded.copyTo(localOriginal, overwrite = true)
                return localOriginal
            }
        }

        return drive.cachedFile(driveId)
    }

    // ---------- Tag writes ----------

    /** Store-first tag write for [driveId] (the derived view follows via invalidation). */
    private suspend fun persistItemTags(driveId: String, tags: ClothingTags) {
        val (fid, item) = itemStore.find(driveId) ?: return
        runCatching { itemStore.upsert(fid, item.copy(tags = tags)) }
    }

    /** Store-first tag write + § 2 sidecar-sync enqueue (manual tag edit). */
    fun updateTags(driveId: String, tags: ClothingTags) {
        scope.launch(Dispatchers.IO) {
            persistItemTags(driveId, tags)
            sidecarQueue.enqueue(driveId)
        }
    }

    /** Re-detect tags for [driveId] via Gemini, then persist store-first + enqueue the sync. */
    fun tagImage(driveId: String) {
        scope.launch {
            _progress.update { it.copy(processingImageId = driveId) }
            val cachedFile = drive.cachedFile(driveId)
                ?: run { _progress.update { it.copy(processingImageId = null) }; return@launch }
            val result = gemini.classifyClothing(cachedFile, geminiLanguage)
            if (result is AiResult.InsufficientCredits) {
                _progress.update { it.copy(processingImageId = null) }
                return@launch
            }
            val tags = result.getOrNull()
                ?: run { _progress.update { it.copy(processingImageId = null) }; return@launch }
            _progress.update { it.copy(processingImageId = null) }
            withContext(Dispatchers.IO) {
                persistItemTags(driveId, tags)
                sidecarQueue.enqueue(driveId)
            }
        }
    }

    // ---------- Image ops ----------

    /** Re-run background removal on the item's original, replacing the cutout bytes in place. */
    fun reprocessBackground(driveId: String) {
        scope.launch {
            _progress.update { it.copy(isProcessing = true, processingImageId = driveId) }
            _errors.emit(null)
            val source = resolveOriginalFile(driveId)
                ?: run {
                    _progress.update { it.copy(isProcessing = false) }
                    _errors.emit(context.localized().getString(R.string.error_original_unavailable))
                    return@launch
                }
            val result = gemini.removeBackground(source, drive.cacheDir)
            if (result is AiResult.InsufficientCredits) {
                _progress.update { it.copy(isProcessing = false, processingImageId = null) }
                return@launch
            }
            val processedFile = result.getOrNull()
                ?: run { _progress.update { it.copy(isProcessing = false, processingImageId = null) }; return@launch }

            _progress.update { it.copy(isProcessing = false, isUploading = true) }
            runCatching {
                drive.updateImage(driveId, processedFile)
                val displayCache = File(drive.cacheDir, "$driveId.png")
                processedFile.copyTo(displayCache, overwrite = true)
                displayCache.absolutePath
            }.onSuccess {
                // Same driveId, new bytes — bump the Coil version; the derived view re-emits.
                itemVersions.bump(driveId)
                _progress.update { it.copy(isUploading = false, processingImageId = null) }
            }.onFailure { e ->
                _progress.update { it.copy(isUploading = false, processingImageId = null) }
                _errors.emit(e.message)
            }
        }
    }

    /** Rotate the cutout (and original, if any) by 90°, refresh the display, upload silently. */
    fun rotateImage(driveId: String) {
        scope.launch {
            val row = withContext(Dispatchers.IO) { itemStore.find(driveId) }?.second ?: return@launch
            jobLock.acquire()
            try {
                // Rotate local cache files immediately so the UI can refresh without waiting.
                withContext(Dispatchers.IO) {
                    val cutoutFile = drive.cachedFile(driveId)
                        ?: drive.downloadToCache(driveId, "$driveId${DriveRepository.CUTOUT_SUFFIX}")
                    if (cutoutFile != null) rotateBitmapFileBy90(cutoutFile)
                    row.originalDriveId?.let { origId ->
                        val origFile = drive.cachedFile(origId)
                            ?: drive.downloadToCache(origId, "$driveId${DriveRepository.ORIGINAL_SUFFIX}")
                        if (origFile != null) rotateBitmapFileBy90(origFile)
                    }
                }
                // Bump version so Coil reloads from the already-rotated local cache (no
                // metadata changed, so no store write is needed).
                itemVersions.bump(driveId)
                // Upload rotated files to Drive silently (no processingImageId = no overlay).
                withContext(Dispatchers.IO) {
                    drive.cachedFile(driveId)?.let { drive.updateImage(driveId, it) }
                    row.originalDriveId?.let { origId ->
                        drive.cachedFile(origId)?.let { drive.updateImage(origId, it) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "rotateImage failed", e)
                _errors.emit(e.message)
            } finally {
                jobLock.release()
            }
        }
    }

    /** Detect + repair black-bg / green-halo / interior-hole issues on a single cutout, then
     *  re-upload preserving the Drive ID. Failures surface as a transient error banner. */
    fun fixCutoutBgForItem(
        driveId: String,
        actions: CutoutFixActions = CutoutFixActions(
            blackToAlpha = true,
            despillGreen = true,
            feather = true,
            tightCrop = true,
        ),
    ) {
        if (!actions.any) return
        scope.launch {
            val row = withContext(Dispatchers.IO) { itemStore.find(driveId) }?.second ?: return@launch
            jobLock.acquire()
            _progress.update { it.copy(processingImageId = driveId) }
            withContext(Dispatchers.IO) {
                try {
                    val local = drive.cachedFile(driveId)
                        ?: drive.downloadToCache(driveId, row.name)
                        ?: return@withContext
                    val tmp = File(drive.cacheDir, "${driveId}_fix.png")
                    fixCutoutBackground(local, tmp, actions)
                    drive.updateImage(driveId, tmp)
                    val cached = File(drive.cacheDir, "$driveId.png")
                    tmp.copyTo(cached, overwrite = true)
                    tmp.delete()
                    itemVersions.bump(driveId)
                } catch (e: Exception) {
                    Log.w(TAG, "Single-item cutout bg-fix failed for $driveId: ${e.message}", e)
                    _errors.emit(e.message)
                } finally {
                    _progress.update { it.copy(processingImageId = null) }
                    jobLock.release()
                }
            }
        }
    }
}

// ---------- Bitmap rotation helper ----------

internal fun rotateBitmapFileBy90(file: File) {
    val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return
    val matrix = Matrix().apply { postRotate(90f) }
    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    // Re-encode as WebP (alpha-preserving) regardless of the cache file's extension — the
    // rotated bytes are re-uploaded via DriveRepository.updateImage, which sends image/webp.
    ImageEncoding.compressCutout(rotated, file)
}
