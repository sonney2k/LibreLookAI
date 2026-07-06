package com.librelookai.testing

import com.librelookai.data.drive.DriveFileDto
import com.librelookai.data.drive.DriveService
import java.io.File

/**
 * In-memory [DriveService] for fake-based repository tests (refactor § 3 slice 1 / § 8).
 * Reads default to "nothing on Drive" (null / empty) and writes record into public maps, so a
 * test only scripts the surface it exercises; subclass to fault-inject or gate a call. Grown
 * alongside the interface — keep behaviors minimal and deterministic.
 */
open class FakeDriveService : DriveService {

    override val cacheDir: File =
        java.nio.file.Files.createTempDirectory("fake-drive-cache").toFile()

    /** Deterministic folder ids. */
    var rootFolderId = "root"
    var tripsFolderId = "trips-folder"
    var shoppingFolderId = "shopping-folder"

    /** Trip file id → JSON content; listed by [listTripFiles], read by [loadTripJson]. */
    val tripJsonById = linkedMapOf<String, String>()

    /** Scriptable local-cache lookup: driveId → file. [downloadToCache] registers here too. */
    val cached = mutableMapOf<String, File>()

    /** Image files served per folder id by [listFiles]. */
    val filesByFolder = mutableMapOf<String, List<DriveFileDto>>()

    /** Sidecar files served per folder id by [listSidecarFiles]. */
    val sidecarFilesByFolder = mutableMapOf<String, List<DriveFileDto>>()

    /** File contents served by [loadFileContent] (sidecar JSON etc.), keyed by file id. */
    val fileContents = mutableMapOf<String, String>()

    /** File ids fetched via [downloadFileTo], in order. */
    val downloadedFileIds = mutableListOf<String>()

    /** Sidecars upserted via [upsertSidecar]: "folderId/name" → json. */
    val upsertedSidecars = linkedMapOf<String, String>()

    /** `(fileId, fromFolderId, toFolderId)` for every [moveFile] call, in order. */
    val movedFiles = mutableListOf<Triple<String, String, String>>()

    /** Outfits JSON per folder id: read by [loadOutfitsJson], written by [saveOutfitsJson]. */
    val outfitsJsonByFolder = linkedMapOf<String, String>()

    /** Outfit-events JSON written per folder id via [saveOutfitEventsJson]. */
    val outfitEventsJsonByFolder = linkedMapOf<String, String>()

    /** Try-ons index JSON per root folder id (read/write). */
    val tryOnsJsonByRoot = mutableMapOf<String, String>()

    /** Drive file ids passed to [deleteFile]. */
    val deletedFileIds = mutableListOf<String>()

    var listTripFilesCalls = 0
        private set

    /** Hook run at the top of [listTripFiles] — gate it or throw to fault-inject. */
    var onListTripFiles: suspend () -> Unit = {}

    var listFilesCalls = 0
        private set

    /** Hook run at the top of [listFiles] — gate it or throw to fault-inject. */
    var onListFiles: suspend () -> Unit = {}

    var loadTryOnsJsonCalls = 0
        private set

    /** Hook run at the top of [loadTryOnsJson] — gate it or throw to fault-inject. */
    var onLoadTryOnsJson: suspend () -> Unit = {}

    /** Registers a trip's JSON under a deterministic file id. */
    fun putTripJson(tripId: String, json: String) {
        tripJsonById["file-$tripId"] = json
    }

    override fun cachedFile(driveId: String): File? = cached[driveId]

    override suspend fun getOrCreateFolder(): String = rootFolderId

    override suspend fun listFiles(folderId: String): List<DriveFileDto> {
        listFilesCalls++
        onListFiles()
        return filesByFolder[folderId].orEmpty()
    }

    override suspend fun downloadToCache(driveId: String, driveName: String): File? =
        File(cacheDir, driveId).apply { writeText("img-$driveId") }.also { cached[driveId] = it }

    override suspend fun downloadFileTo(fileId: String, dest: File): File? {
        downloadedFileIds += fileId
        dest.writeText("img-$fileId")
        return dest
    }

    override suspend fun deleteFile(fileId: String) {
        deletedFileIds += fileId
    }

    override suspend fun getOrCreateTripsFolder(rootFolderId: String): String = tripsFolderId

    override suspend fun getOrCreateShoppingFolder(rootFolderId: String): String = shoppingFolderId

    override suspend fun listTripFiles(tripsFolderId: String): List<DriveFileDto> {
        listTripFilesCalls++
        onListTripFiles()
        return tripJsonById.keys.map { DriveFileDto(id = it, name = "$it.json") }
    }

    override suspend fun loadTripJson(fileId: String): String? = tripJsonById[fileId]

    override suspend fun loadTryOnsJson(rootFolderId: String): String? {
        loadTryOnsJsonCalls++
        onLoadTryOnsJson()
        return tryOnsJsonByRoot[rootFolderId]
    }

    override suspend fun saveTryOnsJson(rootFolderId: String, json: String) {
        tryOnsJsonByRoot[rootFolderId] = json
    }

    override suspend fun loadOutfitsJson(folderId: String): String? = outfitsJsonByFolder[folderId]

    override suspend fun loadWardrobeMetadataJson(folderId: String): String? = null

    override suspend fun listSidecarFiles(folderId: String): List<DriveFileDto> =
        sidecarFilesByFolder[folderId].orEmpty()

    override suspend fun loadFileContent(fileId: String): String? = fileContents[fileId]

    override suspend fun upsertSidecar(folderId: String, name: String, json: String): String {
        upsertedSidecars["$folderId/$name"] = json
        return "sidecar-$name"
    }

    override suspend fun moveFile(fileId: String, fromFolderId: String, toFolderId: String) {
        movedFiles += Triple(fileId, fromFolderId, toFolderId)
    }

    override suspend fun saveOutfitsJson(folderId: String, json: String) {
        outfitsJsonByFolder[folderId] = json
    }

    override suspend fun saveOutfitEventsJson(folderId: String, json: String) {
        outfitEventsJsonByFolder[folderId] = json
    }

    override suspend fun saveTripJson(tripsFolderId: String, tripId: String, json: String): String {
        tripJsonById["file-$tripId"] = json
        return "file-$tripId"
    }

    override suspend fun deleteTripJson(fileId: String) {
        deleteFile(fileId)
        tripJsonById.remove(fileId)
    }

    override suspend fun findTripFileId(tripsFolderId: String, tripId: String): String? =
        "file-$tripId".takeIf { tripJsonById.containsKey(it) }

    // --- § 3 slice 3: the pipeline / use-case / VM surface ---

    /** `(folderId, fileName)` for every [uploadImage] / [uploadImageWithName] call, in order. */
    val uploadedImages = mutableListOf<Pair<String, String>>()

    /** `(fileId, newName)` for every [renameFile] call, in order. */
    val renamedFiles = mutableListOf<Pair<String, String>>()

    /** File ids passed to [updateImage], in order. */
    val updatedImageIds = mutableListOf<String>()

    private var uploadSeq = 0

    override suspend fun uploadImage(folderId: String, imageFile: File): DriveFileDto {
        uploadedImages += folderId to imageFile.name
        return DriveFileDto(id = "upload-${++uploadSeq}", name = imageFile.name)
    }

    override suspend fun uploadImageWithName(folderId: String, imageFile: File, name: String): DriveFileDto {
        uploadedImages += folderId to name
        return DriveFileDto(id = "upload-${++uploadSeq}", name = name)
    }

    override suspend fun updateImage(fileId: String, imageFile: File) {
        updatedImageIds += fileId
    }

    override suspend fun renameFile(fileId: String, newName: String) {
        renamedFiles += fileId to newName
    }

    override suspend fun listSubfolders(parentFolderId: String): List<DriveFileDto> = emptyList()

    override suspend fun createSubfolder(parentFolderId: String, name: String): String = "subfolder-$name"

    override suspend fun listAllImageFiles(folderId: String): List<DriveFileDto> = emptyList()

    override suspend fun loadOutfitEventsJson(folderId: String): String? = null

    override suspend fun loadPreferencesJson(folderId: String): String? = null

    override suspend fun savePreferencesJson(folderId: String, json: String) {}

    override suspend fun loadLocationsJson(rootFolderId: String): String? = null

    override suspend fun saveLocationsJson(rootFolderId: String, json: String) {}

    override suspend fun loadTokenUsageJsonl(folderId: String): String? = null

    override suspend fun saveTokenUsageJsonl(folderId: String, content: String) {}

    override suspend fun loadTrendsCacheJson(rootFolderId: String): String? = null

    override suspend fun saveTrendsCacheJson(rootFolderId: String, json: String) {}

    override suspend fun uploadTryOnImage(rootFolderId: String, name: String, imageFile: File): String =
        "tryon-upload-${++uploadSeq}"

    override suspend fun uploadProfilePhoto(rootFolderId: String, name: String, imageFile: File): String =
        "profile-upload-${++uploadSeq}"
}
