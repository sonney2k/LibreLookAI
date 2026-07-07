package com.librelookai.settings
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import com.librelookai.data.drive.DriveService
import com.librelookai.data.session.UserPreferencesRepository

// TryOnSlot moved to :core:model (core/model/…/settings/TryOnSlot.kt), same package.

// ProfileUiState moved to :core:model (core/model/…/settings/ProfileUiState.kt), same package.

@HiltViewModel
class ProfileViewModel @Inject constructor(
    app: Application,
    private val drive: DriveService,
    private val prefsRepo: UserPreferencesRepository,
) : AndroidViewModel(app) {
    private val gson = Gson()
    private var folderId: String? = null

    private val _state = MutableStateFlow(
        ProfileUiState(
            preferences = UserPreferences(
                language = cachedLanguage(),
                wardrobeTheme = cachedTheme(),
                appFont = cachedFont(),
            )
        )
    )
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        loadPreferences()
        // Mirror every preferences snapshot (initial cached values, Drive load, each save)
        // into the shared read-path repository — consumers collect it instead of being pushed
        // by AppContent mirrors (refactor § 5 slice 2). This VM stays the only writer.
        viewModelScope.launch {
            state.map { it.preferences }.distinctUntilChanged().collect(prefsRepo::publish)
        }
    }

    // The synchronous pre-load mirror lives in core/common (PreloadedUiPrefs, § 1 slice 5) so
    // Context.localized() and the extracted modules can read it; this VM stays the only writer.
    private fun cachedLanguage(): String = PreloadedUiPrefs.cachedLanguage(getApplication())

    private fun cacheLanguage(language: String) =
        PreloadedUiPrefs.cacheLanguage(getApplication(), language)

    private fun cachedTheme(): String = PreloadedUiPrefs.cachedTheme(getApplication())

    private fun cacheTheme(theme: String) = PreloadedUiPrefs.cacheTheme(getApplication(), theme)

    private fun cachedFont(): String = PreloadedUiPrefs.cachedFont(getApplication())

    private fun cacheFont(font: String) = PreloadedUiPrefs.cacheFont(getApplication(), font)

    companion object {
        fun cachedLanguage(context: Context): String = PreloadedUiPrefs.cachedLanguage(context)

        fun cachedTheme(context: Context): String = PreloadedUiPrefs.cachedTheme(context)

        fun cachedFont(context: Context): String = PreloadedUiPrefs.cachedFont(context)
    }

    fun loadPreferences() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val id = folderId ?: drive.getOrCreateFolder().also { folderId = it }
                val json = drive.loadPreferencesJson(id)
                if (json != null) gson.fromJson(json, UserPreferences::class.java) ?: UserPreferences(language = cachedLanguage())
                else UserPreferences(language = cachedLanguage())
            }.onSuccess { prefs ->
                cacheLanguage(prefs.language)
                cacheTheme(prefs.wardrobeTheme)
                cacheFont(prefs.appFont)
                _state.update { it.copy(preferences = prefs, isLoading = false) }
                refreshTryOnCache(prefs)
            }.onFailure { e ->
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun savePreferences(prefs: UserPreferences) {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, savedSuccessfully = false, error = null) }
            runCatching {
                val id = folderId ?: drive.getOrCreateFolder().also { folderId = it }
                // Preserve try-on Drive IDs from the currently-loaded prefs — UI doesn't manage them.
                val current = _state.value.preferences
                val merged = prefs.copy(
                    tryOnFrontDriveId = current.tryOnFrontDriveId,
                    tryOnSideDriveId  = current.tryOnSideDriveId,
                    tryOnBackDriveId  = current.tryOnBackDriveId,
                )
                drive.savePreferencesJson(id, gson.toJson(merged))
                merged
            }.onSuccess { merged ->
                cacheLanguage(merged.language)
                cacheTheme(merged.wardrobeTheme)
                cacheFont(merged.appFont)
                _state.update { it.copy(preferences = merged, isSaving = false, savedSuccessfully = true) }
            }.onFailure { e ->
                _state.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }

    fun clearSavedFlag() = _state.update { it.copy(savedSuccessfully = false) }
    fun clearError() = _state.update { it.copy(error = null) }

    // ---------- Try-on photo management ----------

    /**
     * Reads [sourceUri] from the content resolver, downscales to ≤ 1280 px, uploads the JPEG
     * to the `_profile` Drive subfolder, and records the resulting Drive ID in the user's
     * preferences. Uses a fixed filename per [slot] so repeated uploads overwrite in-place.
     */
    fun uploadTryOnPhoto(slot: TryOnSlot, sourceUri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(tryOnUploading = it.tryOnUploading + slot, error = null) }
            val result = runCatching {
                val rootId = folderId ?: drive.getOrCreateFolder().also { folderId = it }
                val resized = withContext(Dispatchers.IO) { resizeUriToCache(sourceUri, slot) }
                    ?: error("Failed to read image")
                val driveId = drive.uploadProfilePhoto(rootId, slot.fileName, resized)
                // Move the resized temp into a stable cache path keyed by Drive ID.
                val cached = File(drive.cacheDir, "tryon_${slot.name.lowercase()}_$driveId.jpg")
                resized.copyTo(cached, overwrite = true)
                resized.delete()
                driveId to cached.absolutePath
            }
            result.onSuccess { (driveId, localPath) ->
                val updated = updateSlotId(_state.value.preferences, slot, driveId)
                persistPreferences(updated)
                _state.update {
                    it.copy(
                        preferences = updated,
                        tryOnUploading = it.tryOnUploading - slot,
                        tryOnLocalPaths = it.tryOnLocalPaths + (slot to localPath),
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(tryOnUploading = it.tryOnUploading - slot, error = e.message) }
            }
        }
    }

    /**
     * Deletes the photo for [slot] from Drive (best-effort), removes the local cache, and
     * clears the Drive ID in the user preferences.
     */
    fun deleteTryOnPhoto(slot: TryOnSlot) {
        viewModelScope.launch {
            val driveId = driveIdForSlot(_state.value.preferences, slot)
            runCatching {
                if (driveId.isNotEmpty()) drive.deleteFile(driveId)
            }
            val updated = updateSlotId(_state.value.preferences, slot, "")
            persistPreferences(updated)
            _state.value.tryOnLocalPaths[slot]?.let { File(it).delete() }
            _state.update {
                it.copy(
                    preferences = updated,
                    tryOnLocalPaths = it.tryOnLocalPaths - slot,
                )
            }
        }
    }

    /** Local files that currently back the user's try-on reference photos, in FRONT/SIDE/BACK order. */
    fun tryOnFiles(): List<File> = TryOnSlot.entries.mapNotNull {
        _state.value.tryOnLocalPaths[it]?.let { p -> File(p).takeIf { f -> f.exists() } }
    }

    private suspend fun persistPreferences(prefs: UserPreferences) {
        val id = folderId ?: drive.getOrCreateFolder().also { folderId = it }
        drive.savePreferencesJson(id, gson.toJson(prefs))
    }

    private fun driveIdForSlot(prefs: UserPreferences, slot: TryOnSlot) = when (slot) {
        TryOnSlot.FRONT -> prefs.tryOnFrontDriveId
        TryOnSlot.SIDE  -> prefs.tryOnSideDriveId
        TryOnSlot.BACK  -> prefs.tryOnBackDriveId
    }

    private fun updateSlotId(prefs: UserPreferences, slot: TryOnSlot, value: String) = when (slot) {
        TryOnSlot.FRONT -> prefs.copy(tryOnFrontDriveId = value)
        TryOnSlot.SIDE  -> prefs.copy(tryOnSideDriveId  = value)
        TryOnSlot.BACK  -> prefs.copy(tryOnBackDriveId  = value)
    }

    /** Downloads any missing try-on photo into the local cache so they render immediately. */
    private fun refreshTryOnCache(prefs: UserPreferences) {
        viewModelScope.launch(Dispatchers.IO) {
            TryOnSlot.entries.forEach { slot ->
                val id = driveIdForSlot(prefs, slot)
                if (id.isEmpty()) return@forEach
                val cached = File(drive.cacheDir, "tryon_${slot.name.lowercase()}_$id.jpg")
                val file = if (cached.exists()) cached else drive.downloadFileTo(id, cached)
                file?.let { f ->
                    _state.update { it.copy(tryOnLocalPaths = it.tryOnLocalPaths + (slot to f.absolutePath)) }
                }
            }
        }
    }

    /**
     * Reads [uri] via the content resolver, applies any EXIF orientation, downscales so
     * max(width, height) ≤ 1280, and writes a JPEG (orientation NORMAL) to the cache
     * directory. Returns the cache file, or null on any failure.
     */
    private fun resizeUriToCache(uri: Uri, slot: TryOnSlot): File? {
        val cr = getApplication<Application>().contentResolver
        val tmp = File(drive.cacheDir, "tryon_in_${slot.name.lowercase()}_${System.currentTimeMillis()}.jpg")
        cr.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: return null

        val orientation = ExifInterface(tmp.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val raw = BitmapFactory.decodeFile(tmp.absolutePath) ?: run { tmp.delete(); return null }

        val maxDim = maxOf(raw.width, raw.height)
        val scale = if (maxDim > 1280) 1280f / maxDim else 1f
        val matrix = Matrix().apply {
            if (scale != 1f) postScale(scale, scale)
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { preScale(-1f, 1f); postRotate(90f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { preScale(-1f, 1f); postRotate(-90f) }
            }
        }
        val transformed = if (matrix.isIdentity) raw
        else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            .also { if (it !== raw) raw.recycle() }

        ByteArrayOutputStream().use { baos ->
            transformed.compress(Bitmap.CompressFormat.JPEG, 92, baos)
            tmp.writeBytes(baos.toByteArray())
        }
        transformed.recycle()

        ExifInterface(tmp.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            saveAttributes()
        }
        return tmp
    }
}
