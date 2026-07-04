package com.librelookai.shopping

import com.google.gson.Gson
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.DriveService
import com.librelookai.data.local.WardrobeItemStore
import com.librelookai.data.session.UserPreferencesRepository
import com.librelookai.gemini.AiClient
import com.librelookai.settings.AppLanguage
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.ItemSidecar
import com.librelookai.wardrobe.toCachedItem
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The shopping wishlist's serial bg-removal + tagging worker (refactor § 5 slice 9 — the
 * [com.librelookai.wardrobe.ItemIngestionPipeline] pattern, extracted off
 * [ShoppingClosetViewModel] so a destination-scoped VM fork can't start a duplicate queue):
 * a raw upload is enqueued with its owning folder, the worker turns it into the finished
 * cutout + original + sidecar and swaps the store row — the derived wishlist follows via
 * Room invalidation. Process-scoped so an in-flight job survives leaving the screen.
 */
@Singleton
class ShoppingIngestionQueue @Inject constructor(
    private val drive: DriveService,
    private val gemini: AiClient,
    private val itemStore: WardrobeItemStore,
    prefsRepo: UserPreferencesRepository,
) {
    /** One queued raw upload: the pre-cutout Drive id + the `_shopping/` folder it lives in. */
    private data class Job(val driveId: String, val folderId: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gson = Gson()
    private val workQueue = Channel<Job>(Channel.UNLIMITED)

    /** Queued + in-flight jobs — mirrored into [ShoppingClosetUiState.pendingJobs] by the VM. */
    private val _pendingJobs = MutableStateFlow(0)
    val pendingJobs: StateFlow<Int> = _pendingJobs.asStateFlow()

    /** Error funnel for [ShoppingClosetUiState.error] (worker failures only). */
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /** Gemini-facing language name for classifyClothing, mirrored from the shared prefs repo. */
    private var geminiLanguage: String = "English"

    init {
        scope.launch {
            prefsRepo.preferences.collect { geminiLanguage = AppLanguage.toGeminiName(it.language) }
        }
        scope.launch {
            for (job in workQueue) {
                runCatching { process(job) }
                    .onFailure { e -> e.message?.let { _errors.tryEmit(it) } }
                _pendingJobs.update { maxOf(0, it - 1) }
            }
        }
    }

    /** Queues the raw upload [driveId] (already uploaded + store-row'd) for processing. */
    fun enqueue(driveId: String, folderId: String) {
        _pendingJobs.update { it + 1 }
        workQueue.trySend(Job(driveId, folderId))
    }

    private suspend fun process(job: Job) {
        val folderId = job.folderId
        val rawFile = File(drive.cacheDir, "${job.driveId}_original.jpg")
        if (!rawFile.exists()) return

        // Step 1 — bg removal (fall back to raw on failure).
        val processedFile = gemini.removeBackground(rawFile, drive.cacheDir) ?: rawFile

        // Step 2 — upload cutout, rename to "{cutoutId}_cutout.png".
        val cutoutDrive = runCatching {
            val uploaded = drive.uploadImage(folderId, processedFile)
            drive.renameFile(uploaded.id, "${uploaded.id}${DriveRepository.CUTOUT_SUFFIX}")
            uploaded.copy(name = "${uploaded.id}${DriveRepository.CUTOUT_SUFFIX}")
        }.getOrNull() ?: return

        // Step 3 — upload original as "{cutoutId}_original.jpg" (best effort).
        val originalDriveId = runCatching {
            drive.uploadImageWithName(
                folderId, rawFile, "${cutoutDrive.id}${DriveRepository.ORIGINAL_SUFFIX}",
            ).id
        }.getOrNull()

        // Step 4 — local caches: cutout + original (must precede deleteFile, which clears the local _original.jpg).
        val localCutout = File(drive.cacheDir, "${cutoutDrive.id}.png")
        if (processedFile.absolutePath != localCutout.absolutePath) {
            processedFile.copyTo(localCutout, overwrite = true)
        }
        rawFile.copyTo(File(drive.cacheDir, "${cutoutDrive.id}_original.jpg"), overwrite = true)

        // Step 5 — delete the temporary raw upload.
        runCatching { drive.deleteFile(job.driveId) }

        // Step 6 — make the finished cutout live: replace the raw-id store row with the final
        // cutout entry (the derived view swaps raw → cutout via invalidation). Keep the raw
        // row's createdTimeMs so the item holds its sort position.
        val rawCreated = itemStore.find(job.driveId)?.second?.createdTimeMs
            ?: System.currentTimeMillis()
        var finished = DriveImage(
            driveId = cutoutDrive.id,
            localPath = localCutout.absolutePath,
            name = cutoutDrive.name,
            tags = null,
            originalDriveId = originalDriveId,
            folderId = folderId,
            createdTimeMs = rawCreated,
        )
        runCatching { itemStore.upsert(folderId, finished.toCachedItem(), staleDriveId = job.driveId) }

        // Step 7 — classify tags.
        val tags = gemini.classifyClothing(localCutout, geminiLanguage)
        if (tags != null) {
            finished = finished.copy(tags = tags)
            runCatching { itemStore.upsert(folderId, finished.toCachedItem()) }
        }

        // Step 8 — sidecar.
        val sidecarJson = gson.toJson(ItemSidecar(tags, originalDriveId))
        runCatching {
            drive.upsertSidecar(
                folderId, "${cutoutDrive.id}${DriveRepository.SIDECAR_SUFFIX}", sidecarJson,
            )
        }.onSuccess { sidecarId ->
            runCatching { itemStore.setSidecarId(cutoutDrive.id, sidecarId) }
        }
    }
}
