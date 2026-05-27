package com.librelookai.wardrobe

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.PowerManager
import android.provider.DocumentsContract
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.librelookai.MainActivity
import com.librelookai.R
import com.librelookai.auth.GoogleAuthManager
import com.librelookai.data.drive.DriveFileDto
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.await
import com.librelookai.gemini.ClothingTags
import com.librelookai.gemini.CutoutFixActions
import com.librelookai.gemini.CutoutIssues
import com.librelookai.gemini.GeminiRepository
import com.librelookai.gemini.detectCutoutIssues
import com.librelookai.gemini.fixCutoutBackground
import com.librelookai.ml.EmbeddingService
import com.librelookai.service.JobForegroundService
import com.librelookai.settings.UserPreferences
import com.librelookai.util.isNetworkAvailable
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal val FUZZY_TOKEN_SPLIT = Regex("[^\\p{L}\\p{Nd}]+")

class WardrobeViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        internal const val TAG = "RepairAndSync"
        /** Stricter floor for the Repair & Sync duplicate scan. */
        internal const val REPAIR_DUPE_THRESHOLD = 0.97f
        /** Sidecars at or below this size are {} or {"tags":null} — definitely no tags. */
        internal const val SIDECAR_EMPTY_MAX = 20L
        /** Sidecars at or above this size always contain a ClothingTags object. */
        internal const val SIDECAR_FULL_MIN  = 100L
        /** Cache subdir for the processed-query bitmaps fed to the similarity debug preview. */
        internal const val QUERY_DEBUG_DIR = "wardrobe_query_debug"
    }

    internal val drive = DriveRepository(app, GoogleAuthManager(app))
    internal val gemini = GeminiRepository(app)
    internal val gson = Gson()

    private var jobWakeLock: PowerManager.WakeLock? = null
    private val activeJobCount = AtomicInteger(0)

    internal fun acquireJobWakeLock() {
        if (activeJobCount.getAndIncrement() == 0) {
            // Foreground service keeps the process alive while any job is running
            JobForegroundService.acquire(getApplication())
            // Wake lock keeps the CPU awake if the screen turns off mid-job
            val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
            if (jobWakeLock == null) {
                jobWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LibreLookAI:Jobs")
                    .also { it.setReferenceCounted(false) }
            }
            jobWakeLock!!.acquire(30 * 60 * 1000L) // 30-minute safety timeout
            Log.d(TAG, "Wake lock acquired")
            // Warn the user if battery optimization is not disabled — on many devices (especially
            // OEM-customized ROMs) the OS will kill even foreground services unless the app is
            // explicitly exempted from battery optimization.
            if (!pm.isIgnoringBatteryOptimizations(getApplication<Application>().packageName)) {
                _state.update { it.copy(needsBatteryExemption = true) }
            }
        }
    }

    fun dismissBatteryExemptionWarning() {
        _state.update { it.copy(needsBatteryExemption = false) }
    }

    internal fun releaseJobWakeLock() {
        if (activeJobCount.decrementAndGet() == 0) {
            jobWakeLock?.let { if (it.isHeld) it.release() }
            JobForegroundService.release(getApplication())
            Log.d(TAG, "Wake lock released")
        }
    }

    internal val _state = MutableStateFlow(WardrobeUiState())
    val state: StateFlow<WardrobeUiState> = _state.asStateFlow()

    internal var folderId: String? = null
    private var allFolderIds: List<String>? = null
    /**
     * Every closet folderId the user has configured, regardless of which one is currently
     * displayed. Driven by [setAllConfiguredLocations] from MainActivity. Used to build the
     * cross-closet [WardrobeUiState.allLocationImages] snapshot for similarity search.
     */
    private var allConfiguredFolderIds: List<String> = emptyList()
    /** Gemini-facing language name (e.g. "English", "German") for label generation. */
    internal var geminiLanguage: String = "English"
    /** When non-null, all new photo imports target this folder instead of the active view folder. */
    internal var defaultImportFolderId: String? = null
    /** Mirrors UserPreferences.dedupeOnImport — flips capture/import similarity gate on/off. */
    internal var dedupeOnImport: Boolean = false
    /** Mirrors UserPreferences.dedupeThreshold — cosine cutoff for "this is probably already in your wardrobe". */
    internal var dedupeThreshold: Float = 0.88f
    /** Mirrors UserPreferences.preferLocalBgRemoval — when true, camera/gallery imports route through
     *  the on-device segmenter review screen. URL imports always do. */
    internal var preferLocalBgRemoval: Boolean = false
    /** Mirrors UserPreferences.debugSimilarityPreview — when on, similarity searches return up to
     *  50 matches so the debug breakdown has more candidates to scroll through. */
    internal var debugSimilarityPreview: Boolean = false

    /** Serializes all Drive metadata writes to prevent concurrent saves overwriting each other. */
    private val metaMutex = Mutex()

    /** Holds the audit scan results while waiting for the user to confirm processing. */
    internal var pendingAudit: AuditIntermediate? = null

    /** Active loadImages coroutine — cancelled when a new location is selected. */
    private var loadJob: Job? = null

    /**
     * Tracks items recently moved into another closet. Drive's `q='<folder>' in parents` query
     * is eventually consistent — after a move PATCH the destination folder's listing can lag
     * by up to a minute. We use this map to (a) re-inject moved items into Phase 2 results
     * while Drive catches up, so they don't briefly disappear after [setLocation] to the target.
     * Keyed by cutout driveId → (targetFolderId, expiresAtMs).
     */
    private val recentlyMovedItems = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private val recentlyMovedTtlMs: Long = 2 * 60 * 1000L

    private fun pruneRecentlyMoved() {
        val now = System.currentTimeMillis()
        recentlyMovedItems.entries.removeAll { it.value.second < now }
    }

    /**
     * Background processing queue. Each item represents one uploaded photo that needs
     * bg removal + classification. Processed serially so metadata writes are consistent.
     */
    internal val workQueue = Channel<PendingJob>(Channel.UNLIMITED)

    init {
        viewModelScope.launch { processQueue() }
    }

    fun setLanguage(geminiName: String) {
        geminiLanguage = geminiName
    }

    /** Push UserPreferences-derived similarity settings into the VM. Called from MainActivity. */
    fun setDedupeSettings(enabled: Boolean, threshold: Float) {
        dedupeOnImport = enabled
        dedupeThreshold = threshold
    }

    /** Push UserPreferences.preferLocalBgRemoval into the VM. Called from MainActivity. */
    fun setPreferLocalBgRemoval(enabled: Boolean) { preferLocalBgRemoval = enabled }

    /** Push UserPreferences.debugSimilarityPreview into the VM. Called from MainActivity. */
    fun setDebugSimilarityPreview(enabled: Boolean) { debugSimilarityPreview = enabled }

    /** Set the folder that new photo imports target. Null = fall back to the active view folder. */
    fun setDefaultImportFolderId(folderId: String?) {
        defaultImportFolderId = folderId
        _state.update { it.copy(importTargetFolderId = folderId) }
    }

    fun setLocation(newFolderId: String) {
        if (folderId == newFolderId && allFolderIds == null) return
        folderId = newFolderId
        allFolderIds = null
        // Preserve [allLocationImages] across the location switch so similarity search keeps
        // working immediately — it is independent of the active filter.
        _state.update { WardrobeUiState(isLoading = true, allLocationImages = it.allLocationImages, pendingScrollDriveId = it.pendingScrollDriveId) }
        loadImages()
    }

    fun setAllLocations(folderIds: List<String>) {
        if (folderId == null && allFolderIds?.toSet() == folderIds.toSet()) return
        folderId = null
        allFolderIds = folderIds.toList()
        _state.update { WardrobeUiState(isLoading = true, allLocationImages = it.allLocationImages, pendingScrollDriveId = it.pendingScrollDriveId) }
        loadImages()
    }

    /**
     * Tell the VM about every configured closet so it can keep [WardrobeUiState.allLocationImages]
     * in sync. Called by `MainActivity` whenever the locations list changes. The snapshot is
     * read from each per-folder cache file (no Drive calls); folders not yet downloaded simply
     * contribute zero items until the user visits them.
     */
    fun setAllConfiguredLocations(folderIds: List<String>) {
        if (allConfiguredFolderIds.toSet() == folderIds.toSet()) return
        allConfiguredFolderIds = folderIds.toList()
        refreshAllLocationImagesState()
        prefetchUncachedClosets()
    }

    private var prefetchJob: Job? = null

    /**
     * For every configured closet that doesn't have a local cache yet, fetch its files +
     * sidecars from Drive in the background and write the per-folder cache. This ensures
     * similarity search covers all closets on first run, not just the one the user has visited.
     * Skips folders that already have a cache file (those will refresh via normal loadImages).
     */
    private fun prefetchUncachedClosets() {
        if (prefetchJob?.isActive == true) return
        val app = getApplication<Application>()
        if (!app.isNetworkAvailable()) return
        val targets = allConfiguredFolderIds.filter { fid -> !localCacheFile(fid).exists() }
        if (targets.isEmpty()) return
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            targets.forEach { fid ->
                runCatching {
                    val images = loadFolderImages(fid)
                    saveLocalCache(fid, images)
                }
            }
            refreshAllLocationImagesState()
        }
    }

    /** Re-read every configured folder's cache and publish the merged snapshot. */
    internal fun refreshAllLocationImagesState() {
        val merged = allConfiguredFolderIds.flatMap { readCacheAsImages(it) }
        _state.update { it.copy(allLocationImages = merged) }
    }

    private fun readCacheAsImages(fid: String): List<DriveImage> {
        val cacheFile = localCacheFile(fid)
        if (!cacheFile.exists()) return emptyList()
        return runCatching {
            val cache = gson.fromJson(cacheFile.readText(), LocalCache::class.java)
            cache.items.mapNotNull { entry ->
                drive.cachedFile(entry.driveId)?.let { f ->
                    DriveImage(
                        entry.driveId, f.absolutePath, entry.name, entry.tags,
                        originalDriveId = entry.originalDriveId,
                        sidecarDriveId = entry.sidecarDriveId,
                        folderId = fid,
                        createdTimeMs = entry.createdTimeMs,
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Moves the given wardrobe items (cutout + original + sidecar) from the current location
     * folder to [toFolderId]. Removed items are dropped from the in-memory state immediately.
     */
    fun moveItemsToFolder(
        itemIds: List<String>,
        toFolderId: String,
        onDone: (success: Boolean) -> Unit = {},
    ) {
        val fromFolderId = folderId ?: run { onDone(false); return }
        if (fromFolderId == toFolderId) { onDone(true); return }
        viewModelScope.launch {
            val idsSet = itemIds.toSet()
            val toMove = _state.value.images.filter { it.driveId in idsSet }
            runCatching {
                toMove.forEach { item ->
                    drive.moveFile(item.driveId, fromFolderId, toFolderId)
                    item.originalDriveId?.let { drive.moveFile(it, fromFolderId, toFolderId) }
                    item.sidecarDriveId?.let { drive.moveFile(it, fromFolderId, toFolderId) }
                }
            }.onSuccess {
                _state.update { s -> s.copy(images = s.images.filter { it.driveId !in idsSet }) }
                onDone(true)
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
                onDone(false)
            }
        }
    }

    // ---------- Cache ----------

    /** Deletes all locally-cached image files and the JSON index, then re-fetches from Drive. */
    fun clearCacheAndRefresh() {
        val id = folderId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            localCacheFile(id).delete()
            val dir = getApplication<Application>().filesDir.resolve("wardrobe")
            dir.listFiles()?.forEach { it.delete() }
            withContext(Dispatchers.Main) { loadImages() }
        }
    }

    /**
     * Scans [folderIds] on Drive:
     *  1. Renames cutout/original files that don't match the expected naming scheme.
     *  2. Collects originals with no matching cutout (need full AI processing).
     *  3. Collects cutouts missing a tag sidecar (need tagging only).
     * After scanning, pauses with [AuditProgress.awaitingConfirmation] so the UI can
     * show the user what was found before proceeding. Call [continueRepairProcessing]
     * to either process the findings or just reload.
     */
    suspend fun ensureOriginalCached(cutoutDriveId: String): String? = withContext(Dispatchers.IO) {
        val image = _state.value.images.firstOrNull { it.driveId == cutoutDriveId } ?: return@withContext null
        val origId = image.originalDriveId ?: return@withContext null
        val local = File(drive.cacheDir, "${cutoutDriveId}_original.jpg")
        if (local.exists()) return@withContext local.absolutePath
        val downloaded = runCatching { drive.downloadToCache(origId) }.getOrNull() ?: return@withContext null
        runCatching { downloaded.copyTo(local, overwrite = true) }
        local.absolutePath
    }

    internal fun localCacheFile(id: String) =
        File(getApplication<Application>().filesDir, "wardrobe_cache_$id.json")

    internal fun saveLocalCache(id: String, images: List<DriveImage>) {
        runCatching {
            val cache = LocalCache(images.map {
                LocalCacheEntry(it.driveId, it.name, it.tags, it.originalDriveId, it.sidecarDriveId, it.createdTimeMs)
            })
            localCacheFile(id).writeText(gson.toJson(cache))
        }
        // Keep the cross-closet snapshot in sync so similarity search reflects the latest items.
        refreshAllLocationImagesState()
    }

    // ---------- Load ----------

    fun loadImages() {
        loadJob?.cancel()
        pruneRecentlyMoved()
        loadJob = viewModelScope.launch {
            val ids = allFolderIds
            if (ids != null) {
                _state.update { it.copy(isLoading = true, error = null) }
                // Phase 1 — instant: merge caches from all folders
                val cachedAll = ids.flatMap { fid ->
                    val cacheFile = localCacheFile(fid)
                    if (!cacheFile.exists()) return@flatMap emptyList()
                    runCatching {
                        val cache = gson.fromJson(cacheFile.readText(), LocalCache::class.java)
                        cache.items.mapNotNull { entry ->
                            drive.cachedFile(entry.driveId)?.let { f ->
                                DriveImage(entry.driveId, f.absolutePath, entry.name, entry.tags,
                                    originalDriveId = entry.originalDriveId,
                                    sidecarDriveId = entry.sidecarDriveId,
                                    folderId = fid,
                                    createdTimeMs = entry.createdTimeMs)
                            }
                        }
                    }.getOrDefault(emptyList())
                }
                if (cachedAll.isNotEmpty()) _state.update { it.copy(images = cachedAll, isLoading = false) }
                // Phase 2 — network: skip when offline
                _state.update { it.copy(isSyncing = true) }
                if (!getApplication<android.app.Application>().isNetworkAvailable()) {
                    _state.update { it.copy(isLoading = false, isSyncing = false) }
                    return@launch
                }
                runCatching {
                    // Fetch file + sidecar lists for all folders in parallel
                    data class FolderMeta(
                        val id: String,
                        val files: List<DriveFileDto>,
                        val sidecarFiles: List<DriveFileDto>,
                    )
                    val folderMeta = ids.map { fid ->
                        async {
                            val files = async { drive.listFiles(fid) }
                            val sidecars = async { drive.listSidecarFiles(fid) }
                            FolderMeta(fid, files.await(), sidecars.await())
                        }
                    }.awaitAll()

                    val uncachedCount = folderMeta.sumOf { fd -> fd.files.count { drive.cachedFile(it.id) == null } }
                    if (uncachedCount > 0) _state.update { it.copy(syncTotal = uncachedCount, syncDone = 0) }
                    val doneCount = AtomicInteger(0)

                    // Download all uncached images in parallel across all folders
                    folderMeta.flatMap { fd ->
                        fd.files.map { file ->
                            async {
                                val cached = drive.cachedFile(file.id)
                                val r = cached ?: drive.downloadToCache(file.id, file.name)
                                if (cached == null) _state.update { it.copy(syncDone = doneCount.incrementAndGet()) }
                                r
                            }
                        }
                    }.awaitAll()

                    // Load all sidecar content in parallel
                    val sidecarContent: Map<String, ItemSidecar> = folderMeta.flatMap { fd ->
                        fd.sidecarFiles.map { sf ->
                            async {
                                val itemId = sf.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX)
                                val content = drive.loadFileContent(sf.id)
                                itemId to content?.let {
                                    runCatching { gson.fromJson(it, ItemSidecar::class.java) }.getOrNull()
                                }
                            }
                        }
                    }.awaitAll().mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

                    // Build DriveImage list per folder
                    val allFresh = folderMeta.flatMap { fd ->
                        val sidecarIdByItemId = fd.sidecarFiles.associate { sf ->
                            sf.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX) to sf.id
                        }
                        fd.files.mapNotNull { file ->
                            drive.cachedFile(file.id)?.let { cached ->
                                val sidecar = sidecarContent[file.id]
                                DriveImage(
                                    driveId = file.id,
                                    localPath = cached.absolutePath,
                                    name = file.name,
                                    tags = sidecar?.tags ?: file.appProperties?.toClothingTags(),
                                    originalDriveId = sidecar?.originalDriveId,
                                    sidecarDriveId = sidecarIdByItemId[file.id],
                                    folderId = fd.id,
                                    createdTimeMs = file.createdTimeMs,
                                )
                            }
                        }
                    }
                    val freshIds = allFresh.map { it.driveId }.toSet()
                    val pendingRaw = _state.value.images.filter { img ->
                        img.driveId !in freshIds && (
                            !img.name.endsWith(DriveRepository.CUTOUT_SUFFIX) ||
                                recentlyMovedItems[img.driveId]?.first == img.folderId
                            )
                    }
                    allFresh + pendingRaw
                }.onSuccess { images ->
                    _state.update { it.copy(images = images, isLoading = false, syncTotal = 0, syncDone = 0, isSyncing = false) }
                }.onFailure { e ->
                    _state.update { s ->
                        s.copy(isLoading = false, syncTotal = 0, syncDone = 0, isSyncing = false, error = if (s.images.isEmpty()) e.message else null)
                    }
                }
                return@launch
            }

            val id = folderId ?: return@launch
            _state.update { it.copy(isLoading = true, error = null) }

            // Phase 1 — instant: show whatever is already on disk (zero network calls)
            val cacheFile = localCacheFile(id)
            if (cacheFile.exists()) {
                runCatching {
                    val cache = gson.fromJson(cacheFile.readText(), LocalCache::class.java)
                    cache.items.mapNotNull { entry ->
                        drive.cachedFile(entry.driveId)?.let { f ->
                            DriveImage(entry.driveId, f.absolutePath, entry.name, entry.tags,
                                originalDriveId = entry.originalDriveId,
                                sidecarDriveId = entry.sidecarDriveId,
                                folderId = id,
                                createdTimeMs = entry.createdTimeMs)
                        }
                    }
                }.onSuccess { items ->
                    if (items.isNotEmpty()) {
                        _state.update { it.copy(images = items, isLoading = false) }
                    }
                }
            }

            // Phase 2 — background sync: skip when offline
            _state.update { it.copy(isSyncing = true) }
            if (!getApplication<android.app.Application>().isNetworkAvailable()) {
                _state.update { it.copy(isLoading = false, isSyncing = false) }
                return@launch
            }

            runCatching {
                val filesDeferred = async { drive.listFiles(id) }
                val sidecarFilesDeferred = async { drive.listSidecarFiles(id) }
                val files = filesDeferred.await()
                val sidecarFiles = sidecarFilesDeferred.await()

                // Map cutout Drive ID → sidecar file ID (sidecar is named "{cutoutId}.json")
                val sidecarIdByItemId: Map<String, String> = sidecarFiles.associate { sf ->
                    sf.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX) to sf.id
                }

                // Download any uncached image files in parallel, tracking progress
                val uncachedCount = files.count { drive.cachedFile(it.id) == null }
                if (uncachedCount > 0) _state.update { it.copy(syncTotal = uncachedCount, syncDone = 0) }
                val doneCount = AtomicInteger(0)
                files.map { file ->
                    async {
                        val cached = drive.cachedFile(file.id)
                        val r = cached ?: drive.downloadToCache(file.id, file.name)
                        if (cached == null) _state.update { it.copy(syncDone = doneCount.incrementAndGet()) }
                        r
                    }
                }.awaitAll()

                // Load sidecar content in parallel
                val sidecarContent: Map<String, ItemSidecar> = sidecarFiles
                    .map { sf ->
                        async {
                            val itemId = sf.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX)
                            val content = drive.loadFileContent(sf.id)
                            itemId to content?.let {
                                runCatching { gson.fromJson(it, ItemSidecar::class.java) }.getOrNull()
                            }
                        }
                    }
                    .awaitAll()
                    .mapNotNull { (k, v) -> v?.let { k to it } }
                    .toMap()

                // Legacy metadata fallback — only fetch if no sidecars exist yet (migration)
                val legacyMeta: Map<String, WardrobeItemMeta> = if (sidecarFiles.isEmpty()) {
                    drive.loadWardrobeMetadataJson(id)?.let { json ->
                        runCatching {
                            gson.fromJson(json, WardrobeMetadata::class.java).items.associateBy { it.name }
                        }.getOrDefault(emptyMap())
                    } ?: emptyMap()
                } else emptyMap()

                val freshImages = files.mapNotNull { file ->
                    drive.cachedFile(file.id)?.let { cached ->
                        val sidecar = sidecarContent[file.id]
                        val legacy = legacyMeta[file.name]
                        val tags = sidecar?.tags ?: legacy?.tags ?: file.appProperties?.toClothingTags()
                        val originalId = sidecar?.originalDriveId ?: legacy?.originalDriveId
                        DriveImage(
                            driveId = file.id,
                            localPath = cached.absolutePath,
                            name = file.name,
                            tags = tags,
                            originalDriveId = originalId,
                            sidecarDriveId = sidecarIdByItemId[file.id],
                            folderId = id,
                            createdTimeMs = file.createdTimeMs,
                        )
                    }
                }

                // Preserve raw/pending uploads that are in the queue but not yet on Drive as
                // cutouts, plus items recently moved into this folder whose new parent Drive
                // hasn't propagated to `q='<id>' in parents` yet (eventual consistency).
                val freshIds = freshImages.map { it.driveId }.toSet()
                val pendingRaw = _state.value.images.filter { img ->
                    img.driveId !in freshIds && (
                        !img.name.endsWith(DriveRepository.CUTOUT_SUFFIX) ||
                            recentlyMovedItems[img.driveId]?.first == id
                        )
                }

                // Migrate legacy items to sidecars fire-and-forget
                if (legacyMeta.isNotEmpty()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        freshImages.filter { it.sidecarDriveId == null }.forEach { img ->
                            runCatching {
                                val sidecar = ItemSidecar(img.tags, img.originalDriveId)
                                val sidecarFileId = drive.upsertSidecar(
                                    id, "${img.driveId}${DriveRepository.SIDECAR_SUFFIX}",
                                    gson.toJson(sidecar),
                                )
                                _state.update { s ->
                                    s.copy(images = s.images.map { i ->
                                        if (i.driveId == img.driveId) i.copy(sidecarDriveId = sidecarFileId) else i
                                    })
                                }
                            }
                        }
                        saveLocalCache(id, _state.value.images)
                    }
                }

                freshImages + pendingRaw
            }.onSuccess { images ->
                _state.update { it.copy(images = images, isLoading = false, syncTotal = 0, syncDone = 0, isSyncing = false) }
                saveLocalCache(id, images)
            }.onFailure { e ->
                // Don't overwrite cached items already shown with an error banner
                _state.update { s ->
                    s.copy(isLoading = false, syncTotal = 0, syncDone = 0, isSyncing = false, error = if (s.images.isEmpty()) e.message else null)
                }
            }
        }
    }

    /** Loads images from a single Drive folder (Phase 2 network only, no legacy migration). */
    private suspend fun loadFolderImages(id: String): List<DriveImage> = coroutineScope {
        val filesDeferred = async { drive.listFiles(id) }
        val sidecarFilesDeferred = async { drive.listSidecarFiles(id) }
        val files = filesDeferred.await()
        val sidecarFiles = sidecarFilesDeferred.await()

        val sidecarIdByItemId: Map<String, String> = sidecarFiles.associate { sf ->
            sf.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX) to sf.id
        }

        files.map { file ->
            async { drive.cachedFile(file.id) ?: drive.downloadToCache(file.id, file.name) }
        }.awaitAll()

        val sidecarContent: Map<String, ItemSidecar> = sidecarFiles
            .map { sf ->
                async {
                    val itemId = sf.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX)
                    val content = drive.loadFileContent(sf.id)
                    itemId to content?.let {
                        runCatching { gson.fromJson(it, ItemSidecar::class.java) }.getOrNull()
                    }
                }
            }
            .awaitAll()
            .mapNotNull { (k, v) -> v?.let { k to it } }
            .toMap()

        files.mapNotNull { file ->
            drive.cachedFile(file.id)?.let { cached ->
                val sidecar = sidecarContent[file.id]
                val tags = sidecar?.tags ?: file.appProperties?.toClothingTags()
                val originalId = sidecar?.originalDriveId
                DriveImage(
                    driveId = file.id,
                    localPath = cached.absolutePath,
                    name = file.name,
                    tags = tags,
                    originalDriveId = originalId,
                    sidecarDriveId = sidecarIdByItemId[file.id],
                    folderId = id,
                )
            }
        }
    }

    /**
     * Saves a per-item sidecar JSON for [driveId] to Drive, and updates local disk cache.
     * Each item has its own file ("${driveId}.json") — no mutex needed; writes are independent.
     */
    internal fun saveSidecar(driveId: String) {
        val img = _state.value.images.find { it.driveId == driveId } ?: return
        // Resolve the owning folder from the image itself — `folderId` is null when the
        // user is browsing "All locations", but each DriveImage already knows its closet.
        val id = img.folderId.ifEmpty { folderId.orEmpty() }.ifEmpty { return }
        // Write local cache eagerly so the edit survives restart even if the Drive PATCH
        // is still in flight or fails. Drive write happens after, and updates the cache
        // again with the sidecarDriveId once it returns.
        saveLocalCache(id, _state.value.images.filter { it.folderId == id })
        viewModelScope.launch(Dispatchers.IO) {
            val sidecarJson = gson.toJson(ItemSidecar(img.tags, img.originalDriveId))
            val sidecarFileId = try {
                drive.upsertSidecar(id, "$driveId${DriveRepository.SIDECAR_SUFFIX}", sidecarJson)
            } catch (e: Exception) {
                Log.e("WardrobeVM", "saveSidecar failed for $driveId", e)
                _state.update { it.copy(error = "Tag save to Drive failed: ${e.message}") }
                return@launch
            }
            metaMutex.withLock {
                _state.update { s ->
                    s.copy(images = s.images.map { i ->
                        if (i.driveId == driveId) i.copy(sidecarDriveId = sidecarFileId) else i
                    })
                }
                saveLocalCache(id, _state.value.images.filter { it.folderId == id })
            }
        }
    }

    // ---------- Naming helpers ----------

    /**
     * Uploads [imageFile] to [folderId], then immediately renames it to
     * "{driveId}_cutout.png" (where driveId is the Drive-assigned ID).
     * Returns the DriveFileDto with [name] already set to the final name.
     */
    internal suspend fun uploadAsCutout(folderId: String, imageFile: File): DriveFileDto {
        val uploaded = drive.uploadImage(folderId, imageFile)
        val finalName = "${uploaded.id}${DriveRepository.CUTOUT_SUFFIX}"
        runCatching { drive.renameFile(uploaded.id, finalName) }
        return uploaded.copy(name = finalName)
    }

    /**
     * Uploads [imageFile] to [folderId] with filename "{cutoutDriveId}_original.jpg".
     * Returns the new Drive file ID.
     */
    internal suspend fun uploadAsOriginal(folderId: String, imageFile: File, cutoutDriveId: String): String =
        drive.uploadImageWithName(folderId, imageFile, "$cutoutDriveId${DriveRepository.ORIGINAL_SUFFIX}").id

    /**
     * Resolves the Drive ID of a cutout item given its [metaName] (possibly old-format).
     * Checks by Drive ID directly (new format "{id}_cutout.png") or by filename (old formats).
     */
    private fun resolveCutoutDriveId(
        metaName: String,
        fileByName: Map<String, DriveFileDto>,
        fileById: Map<String, DriveFileDto>,
    ): String? {
        if (metaName.endsWith(DriveRepository.CUTOUT_SUFFIX)) {
            val possibleId = metaName.removeSuffix(DriveRepository.CUTOUT_SUFFIX)
            if (fileById.containsKey(possibleId)) return possibleId
        }
        return fileByName[metaName]?.id
    }

    // ---------- Background processing queue ----------

    /** Drains [workQueue] serially — bg removal + tagging for each newly uploaded photo. */
    private suspend fun processQueue() {
        for (job in workQueue) {
            acquireJobWakeLock()
            try {
                runCatching { processQueuedImage(job) }
                    .onFailure { e -> _state.update { it.copy(error = e.message) } }
                _state.update { s ->
                    s.copy(
                        pendingJobs = maxOf(0, s.pendingJobs - 1),
                        processingImageId = if (s.processingImageId == job.driveId)
                            null else s.processingImageId,
                    )
                }
                // Sidecar is saved inside processQueuedImage; update local cache here
                folderId?.let { id -> saveLocalCache(id, _state.value.images) }
            } finally {
                releaseJobWakeLock()
            }
        }
    }

    private suspend fun processQueuedImage(job: PendingJob) {
        val rawFile = File(drive.cacheDir, "${job.driveId}_original.jpg")
        if (!rawFile.exists()) return
        _state.update { it.copy(processingImageId = job.driveId) }
        // NOTE: we intentionally do NOT check if job.driveId is still in state — loadImages()
        // may have replaced it with the cutout ID already (race window). We always process.

        // Step 1 — background removal. If the user produced a local cutout via the on-device
        // segmenter review (LocalBgRemovalScreen), use it as-is and skip the paid Gemini call.
        val processedFile: File = job.prebuiltCutoutPath
            ?.let { File(it) }
            ?.takeIf { it.exists() }
            ?: (gemini.removeBackground(rawFile, drive.cacheDir) ?: rawFile)

        // Step 2 — upload cutout, then rename to "{cutoutId}_cutout.png"
        val cutoutDrive = runCatching { uploadAsCutout(job.folderId, processedFile) }.getOrNull() ?: return

        // Step 3 — upload original as "{cutoutId}_original.jpg" (best-effort)
        val originalDriveId = runCatching {
            uploadAsOriginal(job.folderId, rawFile, cutoutDrive.id)
        }.getOrNull()

        // Step 4 — write cutout to local cache; also cache original for fast future reprocessing
        // (must happen before deleteFile, which also removes the local _original.jpg)
        val localCutout = File(drive.cacheDir, "${cutoutDrive.id}.png")
        if (processedFile.absolutePath != localCutout.absolutePath) {
            processedFile.copyTo(localCutout, overwrite = true)
        }
        rawFile.copyTo(File(drive.cacheDir, "${cutoutDrive.id}_original.jpg"), overwrite = true)

        // Step 5 — delete the temporary raw upload
        runCatching { drive.deleteFile(job.driveId) }

        // Step 6 — update state. Match by rawId OR cutoutId: if loadImages() ran concurrently
        // it may have already placed the item in state with cutoutDrive.id.
        _state.update { s ->
            s.copy(
                images = s.images.map { img ->
                    if (img.driveId == job.driveId || img.driveId == cutoutDrive.id) img.copy(
                        driveId    = cutoutDrive.id,
                        name       = cutoutDrive.name,          // "{cutoutId}_cutout.png"
                        localPath  = localCutout.absolutePath,
                        version    = System.currentTimeMillis(),
                        originalDriveId = originalDriveId,
                    ) else img
                },
                processingImageId = if (s.processingImageId == job.driveId)
                    cutoutDrive.id else s.processingImageId,
            )
        }

        // Step 7 — classify clothing tags
        val tags = gemini.classifyClothing(localCutout, geminiLanguage)
        if (tags != null) {
            _state.update { s ->
                s.copy(images = s.images.map { img ->
                    if (img.driveId == cutoutDrive.id) img.copy(tags = tags) else img
                })
            }
        }

        // Step 8 — save sidecar (includes tags even if null, so item is discoverable on next load)
        val sidecarJson = gson.toJson(ItemSidecar(tags, originalDriveId))
        runCatching {
            drive.upsertSidecar(
                job.folderId, "${cutoutDrive.id}${DriveRepository.SIDECAR_SUFFIX}", sidecarJson,
            )
        }.onSuccess { sidecarId ->
            _state.update { s ->
                s.copy(images = s.images.map { img ->
                    if (img.driveId == cutoutDrive.id) img.copy(sidecarDriveId = sidecarId) else img
                })
            }
        }
        _state.update { s ->
            if (s.processingImageId == cutoutDrive.id || s.processingImageId == job.driveId)
                s.copy(processingImageId = null) else s
        }
    }

    // ---------- Navigation ----------

    fun requestScrollToImage(driveId: String) =
        _state.update { it.copy(pendingScrollDriveId = driveId) }

    /** Consumed by the wardrobe screen after it has scrolled to the pending target. */
    fun consumePendingScroll() = _state.update { it.copy(pendingScrollDriveId = null) }

    // ---------- Upload from camera ----------

    fun reprocessBackground(driveId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isProcessing = true, processingImageId = driveId, error = null) }
            val source = resolveOriginalFile(driveId)
                ?: run {
                    _state.update { it.copy(isProcessing = false, error = "Original not available") }
                    return@launch
                }
            val processedFile = try {
                gemini.removeBackground(source, drive.cacheDir)
            } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                _state.update { it.copy(isProcessing = false, processingImageId = null) }
                return@launch
            } ?: run { _state.update { it.copy(isProcessing = false, processingImageId = null) }; return@launch }

            _state.update { it.copy(isProcessing = false, isUploading = true) }
            runCatching {
                drive.updateImage(driveId, processedFile)
                val displayCache = File(drive.cacheDir, "$driveId.png")
                processedFile.copyTo(displayCache, overwrite = true)
                displayCache.absolutePath
            }.onSuccess { newPath ->
                _state.update { s ->
                    s.copy(
                        isUploading = false,
                        processingImageId = null,
                        images = s.images.map {
                            if (it.driveId == driveId) it.copy(localPath = newPath, version = System.currentTimeMillis())
                            else it
                        },
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(isUploading = false, processingImageId = null, error = e.message) }
            }
        }
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

        val originalDriveId = _state.value.images.find { it.driveId == driveId }?.originalDriveId
        if (originalDriveId != null) {
            val downloaded = drive.downloadToCache(originalDriveId)
            if (downloaded != null) {
                downloaded.copyTo(localOriginal, overwrite = true)
                return localOriginal
            }
        }

        return drive.cachedFile(driveId)
    }

    fun removeAllBackgrounds() {
        val images = _state.value.images
        if (images.isEmpty() || _state.value.isRemovingAllBg) return
        viewModelScope.launch {
            acquireJobWakeLock()
            try {
                _state.update { it.copy(isRemovingAllBg = true, removeBgDone = 0, removeBgTotal = images.size) }
                try {
                images.forEachIndexed { index, image ->
                    _state.update { it.copy(removeBgDone = index) }
                    val source = resolveOriginalFile(image.driveId) ?: return@forEachIndexed
                    val processedFile = gemini.removeBackground(source, drive.cacheDir) ?: return@forEachIndexed

                    // Upload original to Drive if not already stored there
                    val id = folderId ?: return@forEachIndexed
                    val originalDriveId = image.originalDriveId ?: runCatching {
                        drive.uploadImage(id, source).id
                    }.getOrNull()

                    runCatching {
                        drive.updateImage(image.driveId, processedFile)
                        val displayCache = File(drive.cacheDir, "${image.driveId}.png")
                        processedFile.copyTo(displayCache, overwrite = true)
                        _state.update { s ->
                            s.copy(images = s.images.map {
                                if (it.driveId == image.driveId) it.copy(
                                    localPath = displayCache.absolutePath,
                                    version = System.currentTimeMillis(),
                                    originalDriveId = originalDriveId ?: it.originalDriveId,
                                ) else it
                            })
                        }
                    }
                }
                images.forEach { img -> saveSidecar(img.driveId) }
                } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                    // Global dialog appears via CreditsEvents; just abort the bulk.
                }
                _state.update { it.copy(isRemovingAllBg = false, removeBgDone = 0, removeBgTotal = 0) }
            } finally {
                releaseJobWakeLock()
            }
        }
    }

    fun tagImage(driveId: String) {
        viewModelScope.launch {
            _state.update { it.copy(processingImageId = driveId) }
            val cachedFile = drive.cachedFile(driveId)
                ?: run { _state.update { it.copy(processingImageId = null) }; return@launch }
            val tags = try {
                gemini.classifyClothing(cachedFile, geminiLanguage)
            } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                _state.update { it.copy(processingImageId = null) }
                return@launch
            } ?: run { _state.update { it.copy(processingImageId = null) }; return@launch }
            _state.update { s ->
                s.copy(
                    processingImageId = null,
                    images = s.images.map { if (it.driveId == driveId) it.copy(tags = tags) else it },
                )
            }
            saveSidecar(driveId)
        }
    }

    fun updateTags(driveId: String, tags: ClothingTags) {
        _state.update { s ->
            s.copy(images = s.images.map { if (it.driveId == driveId) it.copy(tags = tags) else it })
        }
        saveSidecar(driveId)
    }

    fun retagAll() {
        val images = _state.value.images
        if (images.isEmpty() || _state.value.isRetagging) return
        viewModelScope.launch {
            acquireJobWakeLock()
            try {
            _state.update { it.copy(isRetagging = true, retagDone = 0, retagTotal = images.size) }
            try {
                images.forEachIndexed { index, image ->
                    _state.update { it.copy(retagDone = index) }
                    val cachedFile = drive.cachedFile(image.driveId) ?: return@forEachIndexed
                    val tags = gemini.classifyClothing(cachedFile, geminiLanguage) ?: return@forEachIndexed
                    _state.update { s ->
                        s.copy(images = s.images.map { if (it.driveId == image.driveId) it.copy(tags = tags) else it })
                    }
                }
                images.forEach { img -> saveSidecar(img.driveId) }
            } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                // Global dialog appears via CreditsEvents; abort the bulk.
            }
            _state.update { it.copy(isRetagging = false, retagDone = 0, retagTotal = 0) }
            } finally {
                releaseJobWakeLock()
            }
        }
    }

    // ---------- Move to another location ----------

    fun moveItemsToLocation(driveIds: Set<String>, targetFolderId: String) {
        val toMove = _state.value.images.filter { it.driveId in driveIds }
        if (toMove.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isMoving = true, selectedIds = emptySet(), error = null) }

            // Move all items in parallel; within each item move cutout + original + sidecar in parallel
            val successfulIds = coroutineScope {
                toMove.map { item ->
                    async {
                        val sourceFolderId = item.folderId.ifEmpty { folderId } ?: return@async null
                        if (sourceFolderId == targetFolderId) return@async item.driveId
                        runCatching {
                            val moveOriginal = item.originalDriveId?.let { origId ->
                                async { runCatching { drive.moveFile(origId, sourceFolderId, targetFolderId) } }
                            }
                            val moveSidecar = item.sidecarDriveId?.let { sId ->
                                async { runCatching { drive.moveFile(sId, sourceFolderId, targetFolderId) } }
                            }
                            drive.moveFile(item.driveId, sourceFolderId, targetFolderId)
                            moveOriginal?.await()
                            moveSidecar?.await()
                        }.onFailure { e ->
                            _state.update { it.copy(error = e.message) }
                        }.getOrNull()?.let { item.driveId }
                    }
                }.awaitAll().filterNotNull().toSet()
            }

            if (successfulIds.isNotEmpty()) {
                val movedItems = toMove.filter { it.driveId in successfulIds }
                val affectedFolderIds = movedItems.map { it.folderId }.filter { it.isNotEmpty() }.toSet()
                _state.update { s -> s.copy(images = s.images.filter { it.driveId !in successfulIds }) }
                affectedFolderIds.forEach { fid ->
                    saveLocalCache(fid, _state.value.images.filter { it.folderId == fid })
                }
                notifyItemsMovedTo(targetFolderId, movedItems.map { it.copy(folderId = targetFolderId) })
            }
            _state.update { it.copy(isMoving = false) }
        }
    }

    /**
     * Records that [items] have been moved into [targetFolderId] (their cutout/original/sidecar
     * Drive IDs are unchanged, so the local cache files are still valid). Merges them into the
     * target folder's `wardrobe_cache_*.json` so [setLocation] to that folder shows them in
     * Phase 1 instantly, and remembers them so Phase 2 won't drop them while Drive's listing
     * is still propagating. Callable from other view models (e.g. shopping move-to-closet).
     */
    fun notifyItemsMovedTo(targetFolderId: String, items: List<DriveImage>) {
        if (items.isEmpty() || targetFolderId.isEmpty()) return
        val expiry = System.currentTimeMillis() + recentlyMovedTtlMs
        items.forEach { recentlyMovedItems[it.driveId] = targetFolderId to expiry }
        runCatching {
            val cacheFile = localCacheFile(targetFolderId)
            val existing: List<LocalCacheEntry> = if (cacheFile.exists()) {
                runCatching { gson.fromJson(cacheFile.readText(), LocalCache::class.java).items }
                    .getOrDefault(emptyList())
            } else emptyList()
            val existingIds = existing.map { it.driveId }.toSet()
            val additions = items.filter { it.driveId !in existingIds }.map { img ->
                LocalCacheEntry(img.driveId, img.name, img.tags, img.originalDriveId, img.sidecarDriveId, img.createdTimeMs)
            }
            if (additions.isNotEmpty()) {
                cacheFile.writeText(gson.toJson(LocalCache(existing + additions)))
            }
        }
        // If the target closet is the one currently shown, splice the items in immediately.
        if (folderId == targetFolderId || allFolderIds?.contains(targetFolderId) == true) {
            val currentIds = _state.value.images.map { it.driveId }.toSet()
            val toAdd = items.filter { it.driveId !in currentIds }
            if (toAdd.isNotEmpty()) {
                _state.update { s -> s.copy(images = s.images + toAdd) }
            }
        }
        refreshAllLocationImagesState()
    }

    // ---------- SAF Import ----------

    /**
     * Imports all images from [treeUri] into the current wardrobe folder.
     *
     * Images are always uploaded/updated and immediately visible — AI processing is optional.
     *
     * [removeBackground]   — run Gemini BG removal on each image (5 credits/item).
     * [autoTag]            — classify clothing tags with Gemini (2 credits/item).
     * [replaceExisting]    — delete all current wardrobe items before importing (fresh start).
     * [overwriteDuplicates]— when false (default) images whose filename already exists are
     *                        skipped; when true the existing Drive file is replaced in-place.
     *                        Ignored when [replaceExisting] is true.
     */
    fun clearError() = _state.update { it.copy(error = null) }

    // ---------- Selection & Delete ----------

    fun toggleSelection(driveId: String) {
        _state.update { s ->
            val next = s.selectedIds.toMutableSet()
            if (!next.add(driveId)) next.remove(driveId)
            s.copy(selectedIds = next)
        }
    }

    fun selectAll(ids: List<String>) = _state.update { it.copy(selectedIds = it.selectedIds + ids) }

    fun clearSelection() = _state.update { it.copy(selectedIds = emptySet()) }

    fun rotateImage(driveId: String) {
        val img = _state.value.images.find { it.driveId == driveId } ?: return
        viewModelScope.launch {
            acquireJobWakeLock()
            try {
                // Rotate local cache files immediately so the UI can refresh without waiting.
                withContext(Dispatchers.IO) {
                    val cutoutFile = drive.cachedFile(img.driveId)
                        ?: drive.downloadToCache(img.driveId, "${img.driveId}${DriveRepository.CUTOUT_SUFFIX}")
                    if (cutoutFile != null) rotateBitmapFileBy90(cutoutFile)
                    img.originalDriveId?.let { origId ->
                        val origFile = drive.cachedFile(origId)
                            ?: drive.downloadToCache(origId, "${img.driveId}${DriveRepository.ORIGINAL_SUFFIX}")
                        if (origFile != null) rotateBitmapFileBy90(origFile)
                    }
                }
                // Bump version so Coil reloads from the already-rotated local cache.
                _state.update { s ->
                    s.copy(images = s.images.map {
                        if (it.driveId == driveId) it.copy(version = it.version + 1) else it
                    })
                }
                // Upload rotated files to Drive silently (no processingImageId = no overlay).
                withContext(Dispatchers.IO) {
                    drive.cachedFile(img.driveId)?.let { drive.updateImage(img.driveId, it) }
                    img.originalDriveId?.let { origId ->
                        drive.cachedFile(origId)?.let { drive.updateImage(origId, it) }
                    }
                }
                val id = folderId
                if (id != null) saveLocalCache(id, _state.value.images)
            } catch (e: Exception) {
                Log.e("WardrobeVM", "rotateImage failed", e)
                _state.update { it.copy(error = e.message) }
            } finally {
                releaseJobWakeLock()
            }
        }
    }

    fun deleteSelected() = deleteItems(_state.value.selectedIds)

    fun deleteItems(driveIds: Set<String>) {
        val toDelete = driveIds
        if (toDelete.isEmpty()) return
        val items = _state.value.images.filter { it.driveId in toDelete }
        viewModelScope.launch {
            val id = folderId
            _state.update { it.copy(isUploading = true, selectedIds = emptySet()) }
            items.forEach { img ->
                runCatching { drive.deleteFile(img.driveId) }
                img.originalDriveId?.let { origId -> runCatching { drive.deleteFile(origId) } }
                img.sidecarDriveId?.let { sId -> runCatching { drive.deleteFile(sId) } }
            }
            _state.update { s ->
                s.copy(
                    isUploading = false,
                    images = s.images.filter { it.driveId !in toDelete }
                )
            }
            if (id != null) saveLocalCache(id, _state.value.images)
        }
    }
}

// ---------- Bitmap rotation helper ----------

internal fun rotateBitmapFileBy90(file: File) {
    val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return
    val matrix = Matrix().apply { postRotate(90f) }
    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    val format = if (file.extension == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
    file.outputStream().use { rotated.compress(format, 95, it) }
}

// ---------- Legacy appProperties → ClothingTags (migration read-path only) ----------

private fun Map<String, String>.toClothingTags(): ClothingTags? {
    val type = getOrDefault("clothing_type", "")
    if (type.isEmpty()) return null
    return ClothingTags(
        type        = type,
        category    = getOrDefault("clothing_category", ""),
        uses        = getOrDefault("clothing_uses",        "").split(",").filter { it.isNotBlank() },
        colors      = getOrDefault("clothing_colors",      "").split(",").filter { it.isNotBlank() },
        seasonality = getOrDefault("clothing_seasonality", "").split(",").filter { it.isNotBlank() },
        aesthetic   = getOrDefault("clothing_aesthetic",   "").split(",").filter { it.isNotBlank() },
        fit         = getOrDefault("clothing_fit",         "").split(",").filter { it.isNotBlank() },
        material    = getOrDefault("clothing_material",    "").split(",").filter { it.isNotBlank() },
        pattern     = getOrDefault("clothing_pattern",     "").split(",").filter { it.isNotBlank() },
    )
}
