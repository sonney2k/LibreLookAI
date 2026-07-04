package com.librelookai.wardrobe

import com.librelookai.data.drive.DriveService
import com.librelookai.data.local.WardrobeItemStore
import com.librelookai.data.session.UserPreferencesRepository
import com.librelookai.gemini.ClothingTags
import com.librelookai.gemini.GeminiRepository
import com.librelookai.gemini.classifyClothing
import com.librelookai.service.JobLock
import com.librelookai.settings.AppLanguage
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Progress of a bulk "re-run AI over the current view" op (retag / re-remove-bg). */
data class BulkAiProgress(
    val isRunning: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
)

/**
 * Settings ▸ Advanced ▸ "Fix AI mistakes" — re-classifies every item in the given snapshot
 * (refactor § 5 slice 6, extracted verbatim from `WardrobeViewModel.retagAll`). Tags go
 * store-first ([persistItemTags] pattern); each item's sidecar save rides the § 2 queue.
 */
@Singleton
class RetagAllUseCase @Inject constructor(
    private val drive: DriveService,
    private val gemini: GeminiRepository,
    private val itemStore: WardrobeItemStore,
    private val sidecarSync: SidecarSyncQueue,
    private val jobLock: JobLock,
    prefsRepo: UserPreferencesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _progress = MutableStateFlow(BulkAiProgress())
    val progress: StateFlow<BulkAiProgress> = _progress.asStateFlow()

    private var geminiLanguage: String = "English"

    init {
        scope.launch { prefsRepo.preferences.collect { geminiLanguage = AppLanguage.toGeminiName(it.language) } }
    }

    fun start(images: List<DriveImage>) {
        if (images.isEmpty() || _progress.value.isRunning) return
        scope.launch {
            jobLock.acquire()
            try {
                _progress.value = BulkAiProgress(isRunning = true, done = 0, total = images.size)
                try {
                    images.forEachIndexed { index, image ->
                        _progress.update { it.copy(done = index) }
                        val cachedFile = drive.cachedFile(image.driveId) ?: return@forEachIndexed
                        val tags = gemini.classifyClothing(cachedFile, geminiLanguage) ?: return@forEachIndexed
                        withContext(Dispatchers.IO) { persistItemTags(image.driveId, tags) }
                    }
                    images.forEach { img -> withContext(Dispatchers.IO) { sidecarSync.enqueue(img.driveId) } }
                } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                    // Global dialog appears via CreditsEvents; abort the bulk.
                }
                _progress.value = BulkAiProgress()
            } finally {
                jobLock.release()
            }
        }
    }

    /** Store-first tag write (the derived view follows via invalidation). */
    private suspend fun persistItemTags(driveId: String, tags: ClothingTags) {
        val (fid, item) = itemStore.find(driveId) ?: return
        runCatching { itemStore.upsert(fid, item.copy(tags = tags)) }
    }
}

/**
 * Wardrobe overflow ▸ "Re-remove backgrounds" — re-runs Gemini bg removal over the snapshot,
 * replacing each item's bytes in place (stable Drive ID) and archiving the original first
 * (refactor § 5 slice 6, extracted verbatim from `WardrobeViewModel.removeAllBackgrounds`).
 */
@Singleton
class RemoveAllBackgroundsUseCase @Inject constructor(
    private val drive: DriveService,
    private val gemini: GeminiRepository,
    private val itemStore: WardrobeItemStore,
    private val sidecarSync: SidecarSyncQueue,
    private val versions: ItemVersions,
    private val jobLock: JobLock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _progress = MutableStateFlow(BulkAiProgress())
    val progress: StateFlow<BulkAiProgress> = _progress.asStateFlow()

    /** [uploadFolderId] is the active closet (null in All-locations — matching the old
     *  `folderId ?: return@forEachIndexed`, items lacking an archived original are skipped). */
    fun start(images: List<DriveImage>, uploadFolderId: String?) {
        if (images.isEmpty() || _progress.value.isRunning) return
        scope.launch {
            jobLock.acquire()
            try {
                _progress.value = BulkAiProgress(isRunning = true, done = 0, total = images.size)
                try {
                    images.forEachIndexed { index, image ->
                        _progress.update { it.copy(done = index) }
                        val source = resolveOriginalFile(image) ?: return@forEachIndexed
                        val processedFile = gemini.removeBackground(source, drive.cacheDir) ?: return@forEachIndexed

                        // Upload original to Drive if not already stored there
                        val id = uploadFolderId ?: return@forEachIndexed
                        val originalDriveId = image.originalDriveId ?: runCatching {
                            drive.uploadImage(id, source).id
                        }.getOrNull()

                        runCatching {
                            drive.updateImage(image.driveId, processedFile)
                            val displayCache = File(drive.cacheDir, "${image.driveId}.png")
                            processedFile.copyTo(displayCache, overwrite = true)
                            // Store-first: stamp the (possibly new) original id onto the row, then
                            // bust the Coil cache — the derived view re-emits both.
                            withContext(Dispatchers.IO) {
                                itemStore.find(image.driveId)?.let { (fid, row) ->
                                    itemStore.upsert(fid, row.copy(originalDriveId = originalDriveId ?: row.originalDriveId))
                                }
                            }
                            versions.bump(image.driveId)
                        }
                    }
                    images.forEach { img -> withContext(Dispatchers.IO) { sidecarSync.enqueue(img.driveId) } }
                } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                    // Global dialog appears via CreditsEvents; just abort the bulk.
                }
                _progress.value = BulkAiProgress()
            } finally {
                jobLock.release()
            }
        }
    }

    /**
     * Best available original for [image]: the canonical `_original.jpg` cache, a Drive
     * download via its `originalDriveId`, or the current cutout as last resort.
     */
    private suspend fun resolveOriginalFile(image: DriveImage): File? {
        val localOriginal = File(drive.cacheDir, "${image.driveId}_original.jpg")
        if (localOriginal.exists()) return localOriginal
        if (image.originalDriveId != null) {
            val downloaded = drive.downloadToCache(image.originalDriveId!!)
            if (downloaded != null) {
                downloaded.copyTo(localOriginal, overwrite = true)
                return localOriginal
            }
        }
        return drive.cachedFile(image.driveId)
    }
}
