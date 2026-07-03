package com.librelookai.data.drive

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File

/**
 * The Drive I/O seam (refactor § 3 slice 1): the surface the § 5 repositories consume, made
 * virtual so fake-based tests can stand in for Drive. Implemented by [DriveRepository] —
 * behavior that used to live in same-package extension functions is un-fakeable (extensions
 * resolve statically), so each absorbed method is an interface member delegating to the
 * renamed `internal` `<name>Impl` extension in its domain file.
 *
 * Grown incrementally per consumer slice (§ 3 slice 2: sync handlers; slice 3: pipeline /
 * use-cases / VMs) — add a method the moment a consumer of the *interface* needs it, never
 * speculatively.
 */
interface DriveService {

    /** App-private persistent cache for wardrobe images (Phase-1 reads; downloads land here). */
    val cacheDir: File

    /** The locally cached file for a Drive ID, or null if not yet downloaded. */
    fun cachedFile(driveId: String): File?

    /** The app root folder's Drive ID, creating/re-adopting it as needed. */
    suspend fun getOrCreateFolder(): String

    /** Wardrobe image (cutout) files directly inside [folderId], newest first. */
    suspend fun listFiles(folderId: String): List<DriveFileDto>

    /**
     * Downloads [driveId] into [cacheDir] (no-op when already cached). Pass [driveName] so
     * cutouts keep the correct local extension. Null on failure.
     */
    suspend fun downloadToCache(driveId: String, driveName: String = ""): File?

    /** Downloads [fileId] directly to [dest] (bypasses the [cacheDir] naming). Null on error. */
    suspend fun downloadFileTo(fileId: String, dest: File): File?

    /** Permanently deletes [fileId] from Drive and the local cache. */
    suspend fun deleteFile(fileId: String)

    /** Folder ID of the `_trips` subfolder inside [rootFolderId], creating it if needed. */
    suspend fun getOrCreateTripsFolder(rootFolderId: String): String

    /** Folder ID of the `_shopping` subfolder inside [rootFolderId], creating it if needed. */
    suspend fun getOrCreateShoppingFolder(rootFolderId: String): String

    /** Trip JSON files directly inside [tripsFolderId]. */
    suspend fun listTripFiles(tripsFolderId: String): List<DriveFileDto>

    /** A single trip's JSON by Drive file ID, or null on failure. */
    suspend fun loadTripJson(fileId: String): String?

    /** The try-ons index JSON from the root folder, or null if not yet created. */
    suspend fun loadTryOnsJson(rootFolderId: String): String?

    /** Creates or overwrites the try-ons index JSON in the root folder. */
    suspend fun saveTryOnsJson(rootFolderId: String, json: String)

    /** The saved-outfits JSON of [folderId] (current or legacy filename), or null if absent. */
    suspend fun loadOutfitsJson(folderId: String): String?

    /** The wardrobe-metadata JSON of [folderId], or null if absent. */
    suspend fun loadWardrobeMetadataJson(folderId: String): String?

    /** Per-item sidecar JSON files directly inside [folderId] (system JSONs excluded). */
    suspend fun listSidecarFiles(folderId: String): List<DriveFileDto>

    /** Raw text content of [fileId], or null on HTTP failure. */
    suspend fun loadFileContent(fileId: String): String?

    /** Creates or overwrites the sidecar [name] inside [folderId]. Returns its Drive file ID. */
    suspend fun upsertSidecar(folderId: String, name: String, json: String): String
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DriveServiceModule {
    @Binds
    abstract fun bindDriveService(impl: DriveRepository): DriveService
}
