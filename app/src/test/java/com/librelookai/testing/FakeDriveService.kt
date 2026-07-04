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

    /** Sidecars upserted via [upsertSidecar]: "folderId/name" → json. */
    val upsertedSidecars = linkedMapOf<String, String>()

    /** `(fileId, fromFolderId, toFolderId)` for every [moveFile] call, in order. */
    val movedFiles = mutableListOf<Triple<String, String, String>>()

    /** Outfits JSON written per folder id via [saveOutfitsJson]. */
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

    /** Registers a trip's JSON under a deterministic file id. */
    fun putTripJson(tripId: String, json: String) {
        tripJsonById["file-$tripId"] = json
    }

    override fun cachedFile(driveId: String): File? = null

    override suspend fun getOrCreateFolder(): String = rootFolderId

    override suspend fun listFiles(folderId: String): List<DriveFileDto> = emptyList()

    override suspend fun downloadToCache(driveId: String, driveName: String): File? = null

    override suspend fun downloadFileTo(fileId: String, dest: File): File? = null

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

    override suspend fun loadTryOnsJson(rootFolderId: String): String? = tryOnsJsonByRoot[rootFolderId]

    override suspend fun saveTryOnsJson(rootFolderId: String, json: String) {
        tryOnsJsonByRoot[rootFolderId] = json
    }

    override suspend fun loadOutfitsJson(folderId: String): String? = null

    override suspend fun loadWardrobeMetadataJson(folderId: String): String? = null

    override suspend fun listSidecarFiles(folderId: String): List<DriveFileDto> = emptyList()

    override suspend fun loadFileContent(fileId: String): String? = null

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
}
