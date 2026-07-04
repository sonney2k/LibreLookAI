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
 * Grown incrementally per consumer slice (§ 3 slice 2 added the sync-handler surface; slice 3:
 * pipeline / use-cases / VMs) — add a method the moment a consumer of the *interface* needs it,
 * never speculatively.
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

    // --- § 3 slice 2: the sync-handler surface ---

    /**
     * Moves [fileId] from [fromFolderId] to [toFolderId] via a single parent-change PATCH.
     * Drive ID, content, and name are all preserved — no re-upload.
     */
    suspend fun moveFile(fileId: String, fromFolderId: String, toFolderId: String)

    /** Creates or overwrites the saved-outfits JSON (`_outfits.json`) of [folderId]. */
    suspend fun saveOutfitsJson(folderId: String, json: String)

    /** Creates or overwrites the outfit-events (calendar wears) JSON of [folderId]. */
    suspend fun saveOutfitEventsJson(folderId: String, json: String)

    /** Creates or overwrites `{tripId}.json` inside [tripsFolderId]. Returns the Drive file ID. */
    suspend fun saveTripJson(tripsFolderId: String, tripId: String, json: String): String

    /** Deletes a trip JSON by Drive file ID. */
    suspend fun deleteTripJson(fileId: String)

    /**
     * Drive file ID of `{tripId}.json` inside [tripsFolderId], or null when absent. Lets the
     * queued trip-delete handler resolve the file by stable trip id instead of a session-local
     * Drive-id map (which used to orphan the file when a trip was deleted before Phase 2 load).
     */
    suspend fun findTripFileId(tripsFolderId: String, tripId: String): String?

    // --- § 3 slice 3: the pipeline / use-case / VM surface ---

    /** Uploads an image file into [folderId] under its local file name. */
    suspend fun uploadImage(folderId: String, imageFile: File): DriveFileDto

    /** Like [uploadImage] but uses [name] as the Drive filename instead of the local file name. */
    suspend fun uploadImageWithName(folderId: String, imageFile: File, name: String): DriveFileDto

    /** Replaces the content of an existing Drive file in-place (preserves ID and metadata). */
    suspend fun updateImage(fileId: String, imageFile: File)

    /** Renames a Drive file in-place (metadata PATCH only — no content re-upload, ID unchanged). */
    suspend fun renameFile(fileId: String, newName: String)

    /** Immediate subfolders of [parentFolderId], sorted by name. */
    suspend fun listSubfolders(parentFolderId: String): List<DriveFileDto>

    /** Creates (or finds an existing) subfolder [name] inside [parentFolderId]; returns its ID. */
    suspend fun createSubfolder(parentFolderId: String, name: String): String

    /** Every image file inside [folderId] (cutouts, originals, legacy formats). */
    suspend fun listAllImageFiles(folderId: String): List<DriveFileDto>

    /** The outfit-events (calendar wears) JSON of [folderId], or null if absent. */
    suspend fun loadOutfitEventsJson(folderId: String): String?

    /** The user-preferences JSON from the root folder, or null if not yet created. */
    suspend fun loadPreferencesJson(folderId: String): String?

    /** Creates or overwrites the user-preferences JSON in the root folder. */
    suspend fun savePreferencesJson(folderId: String, json: String)

    /** The closet-locations JSON from the root folder, or null if not yet created. */
    suspend fun loadLocationsJson(rootFolderId: String): String?

    /** Creates or overwrites the closet-locations JSON in the root folder. */
    suspend fun saveLocationsJson(rootFolderId: String, json: String)

    /** The token-usage JSONL from the root folder, or null if not yet created. */
    suspend fun loadTokenUsageJsonl(folderId: String): String?

    /** Creates or overwrites the token-usage JSONL in the root folder. */
    suspend fun saveTokenUsageJsonl(folderId: String, content: String)

    /** The shared fashion-trends cache JSON from the root folder, or null if not yet created. */
    suspend fun loadTrendsCacheJson(rootFolderId: String): String?

    /** Creates or overwrites the shared fashion-trends cache JSON in the root folder. */
    suspend fun saveTrendsCacheJson(rootFolderId: String, json: String)

    /** Uploads a try-on result image into `_tryons/` under [rootFolderId]; returns its Drive ID. */
    suspend fun uploadTryOnImage(rootFolderId: String, name: String, imageFile: File): String

    /** Uploads/replaces a profile photo in `_profile/` under [rootFolderId]; returns its Drive ID. */
    suspend fun uploadProfilePhoto(rootFolderId: String, name: String, imageFile: File): String
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DriveServiceModule {
    @Binds
    abstract fun bindDriveService(impl: DriveRepository): DriveService
}
