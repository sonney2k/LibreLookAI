package com.librelookai.wardrobe

import com.librelookai.data.drive.DriveFileDto
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.listSidecarFiles
import com.librelookai.data.drive.loadFileContent
import com.librelookai.data.drive.loadWardrobeMetadataJson
import com.librelookai.data.drive.upsertSidecar
import com.librelookai.gemini.ClothingTags
import com.librelookai.util.ImageEncoding
import com.librelookai.util.isNetworkAvailable
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The two-phase Drive load arms of [WardrobeRepository] (same-package extensions so the
// repository class stays readable — the pattern the pre-slice-9 WardrobeViewModel used).
// Members shared with these are `internal` on the repository.

/** Two-phase load of the current view scope: instant cache paint, then Drive reconcile. */
internal fun WardrobeRepository.reload() {
    loadJob?.cancel()
    pruneRecentlyMoved()
    loadJob = scope.launch {
        val ids = allFolderIds
        if (ids != null) {
            loadAllLocations(ids)
            return@launch
        }

        val id = folderId ?: return@launch
        _syncStatus.update { it.copy(isLoading = true, error = null) }

        // Phase 1 — instant: show whatever is already on disk (zero network calls). The cache
        // read runs off the main thread so switching into a large (warmed) closet doesn't
        // jank the UI. The derived view paints the cache by itself; this read only settles
        // the flags.
        val cachedItems = withContext(Dispatchers.IO) { readCacheAsImages(id) }
        if (cachedItems.isNotEmpty()) {
            _syncStatus.update { it.copy(isLoading = false) }
        }
        // Only a cold restore (empty cache) shows the verbose details/finishing progress; warm
        // reconciles re-fetch sidecars silently (no per-load "loading details" bar).
        val coldLoad = cachedItems.isEmpty()

        // Phase 2 — background sync: skip when offline
        _syncStatus.update { it.copy(isSyncing = true) }
        if (!context.isNetworkAvailable()) {
            _syncStatus.update { it.copy(isLoading = false, isSyncing = false) }
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
            if (uncachedCount > 0) _syncStatus.update {
                it.copy(syncTotal = uncachedCount, syncDone = 0, syncPhase = WardrobeSyncPhase.DOWNLOADING)
            }
            val doneCount = AtomicInteger(0)
            files.map { file ->
                async {
                    val cached = drive.cachedFile(file.id)
                    val r = cached ?: drive.downloadToCache(file.id, file.name)
                    if (cached == null) _syncStatus.update { it.copy(syncDone = doneCount.incrementAndGet()) }
                    r
                }
            }.awaitAll()

            // Load sidecar content in parallel — each is its own Drive fetch, so report it as a
            // counted "details" step (cold load only) instead of leaving the bar full while it runs.
            _syncStatus.update {
                if (coldLoad) it.copy(syncPhase = WardrobeSyncPhase.DETAILS, syncTotal = sidecarFiles.size, syncDone = 0)
                else it.copy(syncPhase = WardrobeSyncPhase.NONE, syncTotal = 0, syncDone = 0)
            }
            val sidecarDone = AtomicInteger(0)
            val sidecarContent: Map<String, ItemSidecar> = sidecarFiles
                .map { sf ->
                    async {
                        val itemId = sf.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX)
                        val content = drive.loadFileContent(sf.id)
                        if (coldLoad) _syncStatus.update { it.copy(syncDone = sidecarDone.incrementAndGet()) }
                        itemId to content?.let {
                            runCatching { gson.fromJson(it, ItemSidecar::class.java) }.getOrNull()
                        }
                    }
                }
                .awaitAll()
                .mapNotNull { (k, v) -> v?.let { k to it } }
                .toMap()

            if (coldLoad) _syncStatus.update { it.copy(syncPhase = WardrobeSyncPhase.FINISHING) }

            // Legacy metadata fallback — only fetch if no sidecars exist yet (migration)
            val legacyMeta: Map<String, WardrobeItemMeta> = if (sidecarFiles.isEmpty()) {
                drive.loadWardrobeMetadataJson(id)?.let { json ->
                    runCatching {
                        gson.fromJson(json, WardrobeMetadata::class.java).items.associateBy { it.name }
                    }.getOrDefault(emptyMap())
                } ?: emptyMap()
            } else emptyMap()

            val freshImages = files.mapNotNull { file ->
                // Suppress an item just moved to a different folder (see recentlyMovedItems):
                // Drive may still list it here until removeParents propagates.
                val movedTo = recentlyMovedItems[file.id]?.first
                if (movedTo != null && movedTo != id) return@mapNotNull null
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
            val pendingRaw = images.value.filter { img ->
                img.driveId !in freshIds && (
                    !ImageEncoding.isCutoutName(img.name) ||
                        recentlyMovedItems[img.driveId]?.first == id
                    )
            }

            // Migrate legacy items to sidecars fire-and-forget; the new sidecar id is
            // stamped onto the Room row and reaches the view via invalidation.
            if (legacyMeta.isNotEmpty()) {
                scope.launch(Dispatchers.IO) {
                    freshImages.filter { it.sidecarDriveId == null }.forEach { img ->
                        runCatching {
                            val sidecar = ItemSidecar(img.tags, img.originalDriveId)
                            val sidecarFileId = drive.upsertSidecar(
                                id, "${img.driveId}${DriveRepository.SIDECAR_SUFFIX}",
                                gson.toJson(sidecar),
                            )
                            itemStore.setSidecarId(img.driveId, sidecarFileId)
                        }
                    }
                }
            }

            freshImages + pendingRaw
        }.onSuccess { loaded ->
            // Store write before the flags clear, so a cold load never flashes empty.
            withContext(Dispatchers.IO) { saveLocalCache(id, loaded) }
            _syncStatus.update {
                it.copy(isLoading = false, syncTotal = 0, syncDone = 0, syncPhase = WardrobeSyncPhase.NONE, isSyncing = false)
            }
        }.onFailure { e ->
            // Don't overwrite cached items already shown with an error banner
            _syncStatus.update {
                it.copy(
                    isLoading = false, syncTotal = 0, syncDone = 0,
                    syncPhase = WardrobeSyncPhase.NONE, isSyncing = false,
                    error = if (images.value.isEmpty()) e.message else null,
                )
            }
        }
    }
}

/** The All-locations arm of [reload]: merge every closet's cache, then reconcile all folders. */
private suspend fun WardrobeRepository.loadAllLocations(ids: List<String>) = coroutineScope {
    _syncStatus.update { it.copy(isLoading = true, error = null) }
    // Phase 1 — instant: merge caches from all folders, off the main thread. The derived
    // view paints the cache by itself; this read only settles the flags.
    val cachedAll = withContext(Dispatchers.IO) { ids.flatMap { fid -> readCacheAsImages(fid) } }
    if (cachedAll.isNotEmpty()) _syncStatus.update { it.copy(isLoading = false) }
    // A cold restore (nothing cached to paint) is the only time we want the verbose
    // download → details → finishing progress; routine warm-cache reconciles re-fetch
    // sidecars too but must stay silent (no per-load "loading details" bar).
    val coldLoad = cachedAll.isEmpty()
    // Phase 2 — network: skip when offline
    _syncStatus.update { it.copy(isSyncing = true) }
    if (!context.isNetworkAvailable()) {
        _syncStatus.update { it.copy(isLoading = false, isSyncing = false) }
        return@coroutineScope
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
        if (uncachedCount > 0) _syncStatus.update {
            it.copy(syncTotal = uncachedCount, syncDone = 0, syncPhase = WardrobeSyncPhase.DOWNLOADING)
        }
        val doneCount = AtomicInteger(0)

        // Download all uncached images in parallel across all folders
        folderMeta.flatMap { fd ->
            fd.files.map { file ->
                async {
                    val cached = drive.cachedFile(file.id)
                    val r = cached ?: drive.downloadToCache(file.id, file.name)
                    if (cached == null) _syncStatus.update { it.copy(syncDone = doneCount.incrementAndGet()) }
                    r
                }
            }
        }.awaitAll()

        // Load all sidecar content in parallel. Each sidecar is a separate Drive fetch,
        // so on a fresh restore this is the slow tail after images finish — surface it as
        // its own counted "details" step (cold load only) rather than letting the bar sit
        // full and idle.
        val sidecarTotal = folderMeta.sumOf { it.sidecarFiles.size }
        _syncStatus.update {
            if (coldLoad) it.copy(syncPhase = WardrobeSyncPhase.DETAILS, syncTotal = sidecarTotal, syncDone = 0)
            else it.copy(syncPhase = WardrobeSyncPhase.NONE, syncTotal = 0, syncDone = 0)
        }
        val sidecarDone = AtomicInteger(0)
        val sidecarContent: Map<String, ItemSidecar> = folderMeta.flatMap { fd ->
            fd.sidecarFiles.map { sf ->
                async {
                    val itemId = sf.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX)
                    val content = drive.loadFileContent(sf.id)
                    if (coldLoad) _syncStatus.update { it.copy(syncDone = sidecarDone.incrementAndGet()) }
                    itemId to content?.let {
                        runCatching { gson.fromJson(it, ItemSidecar::class.java) }.getOrNull()
                    }
                }
            }
        }.awaitAll().mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

        if (coldLoad) _syncStatus.update { it.copy(syncPhase = WardrobeSyncPhase.FINISHING) }
        // Build DriveImage list per folder
        val allFresh = folderMeta.flatMap { fd ->
            val sidecarIdByItemId = fd.sidecarFiles.associate { sf ->
                sf.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX) to sf.id
            }
            fd.files.mapNotNull { file ->
                // Suppress an item that was just moved to a DIFFERENT folder — Drive's
                // per-folder listing is eventually consistent and may still return it
                // from its old parent for minutes, which would otherwise resurrect the
                // stale copy in the source closet (and duplicate it across closets).
                val movedTo = recentlyMovedItems[file.id]?.first
                if (movedTo != null && movedTo != fd.id) return@mapNotNull null
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
        val pendingRaw = images.value.filter { img ->
            img.driveId !in freshIds && (
                !ImageEncoding.isCutoutName(img.name) ||
                    recentlyMovedItems[img.driveId]?.first == img.folderId
                )
        }
        allFresh + pendingRaw
    }.onSuccess { loaded ->
        // Persist each closet's reconciled items to its own per-folder store rows —
        // the derived view repaints from them, and a later switch from All → one
        // closet paints instantly from disk (Phase 1) instead of re-listing + re-
        // fetching every sidecar. Raw in-flight uploads ride along (non-cutout rows
        // survive the next reconcile via pendingRaw, exactly like the single-folder
        // path always cached them). Store writes land before the flags clear so a
        // cold load never flashes an empty grid.
        withContext(Dispatchers.IO) {
            val byFolder = loaded.groupBy { it.folderId }
            ids.forEach { fid -> saveLocalCache(fid, byFolder[fid].orEmpty()) }
        }
        _syncStatus.update {
            it.copy(isLoading = false, syncTotal = 0, syncDone = 0, syncPhase = WardrobeSyncPhase.NONE, isSyncing = false)
        }
    }.onFailure { e ->
        _syncStatus.update {
            it.copy(
                isLoading = false, syncTotal = 0, syncDone = 0,
                syncPhase = WardrobeSyncPhase.NONE, isSyncing = false,
                error = if (images.value.isEmpty()) e.message else null,
            )
        }
    }
}

/** Loads images from a single Drive folder (Phase 2 network only, no legacy migration). */
internal suspend fun WardrobeRepository.loadFolderImages(id: String): List<DriveImage> = coroutineScope {
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
        val movedTo = recentlyMovedItems[file.id]?.first
        if (movedTo != null && movedTo != id) return@mapNotNull null
        drive.cachedFile(file.id)?.let { cached ->
            val sidecar = sidecarContent[file.id]
            DriveImage(
                driveId = file.id,
                localPath = cached.absolutePath,
                name = file.name,
                tags = sidecar?.tags ?: file.appProperties?.toClothingTags(),
                originalDriveId = sidecar?.originalDriveId,
                sidecarDriveId = sidecarIdByItemId[file.id],
                folderId = id,
            )
        }
    }
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
