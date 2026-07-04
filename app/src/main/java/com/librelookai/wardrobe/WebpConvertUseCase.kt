package com.librelookai.wardrobe

import android.graphics.BitmapFactory
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.listAllImageFiles
import com.librelookai.data.local.WardrobeItemStore
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.TryOn
import com.librelookai.service.JobLock
import com.librelookai.util.ImageEncoding
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

/** Progress of the one-shot WebP conversion maintenance op. */
data class ConvertProgress(
    val isConverting: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
)

/**
 * One-shot maintenance op (Settings ▸ Advanced ▸ "Convert images to WebP"): re-encodes every
 * legacy PNG cutout / JPG original / try-on result image to **WebP**, renames the Drive files to
 * `.webp`, and rewrites every persisted *filename* reference so outfits, try-ons, trips and the
 * wardrobe keep resolving (refactor § 5 slice 6, extracted verbatim from
 * `WardrobeViewModel.convertImagesToWebp`).
 *
 * Why this is safe: Drive **file IDs are stable** across [DriveRepository.renameFile] /
 * [DriveRepository.updateImage] (metadata/media PATCH, ID preserved), and identity here is
 * folderId + Drive ID. Sidecars (`{cutoutId}.json`), `originalDriveId`, `imageDriveId`, and every
 * in-memory `itemIds` resolution therefore stay valid. The only persisted things that embed an
 * extension are *filenames* — `Outfit.itemNames`, `TryOn.itemNames`, `TryOn.imageName` — which we
 * rewrite on Drive. Outfits/try-ons rebuild from the rewritten JSON on their next load.
 *
 * Re-encode quality follows the user's [ImageEncoding.tier]. Costs no Gemini/credits. Idempotent:
 * files already ending `.webp` are skipped, so re-running is a no-op.
 */
@Singleton
class WebpConvertUseCase @Inject constructor(
    private val drive: DriveRepository,
    private val itemStore: WardrobeItemStore,
    private val versions: ItemVersions,
    private val jobLock: JobLock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gson = Gson()

    private val _progress = MutableStateFlow(ConvertProgress())
    val progress: StateFlow<ConvertProgress> = _progress.asStateFlow()

    /**
     * [viewImages] is the wardrobe's currently-displayed items: only their store rows are stamped
     * with the renamed filenames here (others pick the new name up on their next load), matching
     * the old `_state.value.images` loop — items with an empty `folderId` (All-locations view)
     * are skipped exactly as before.
     */
    fun start(closetFolderIds: List<String>, viewImages: List<DriveImage>) {
        if (_progress.value.isConverting) return
        jobLock.acquire()
        _progress.value = ConvertProgress(isConverting = true, done = 0, total = 0)
        scope.launch(Dispatchers.IO) {
            try {
                val root = drive.getOrCreateFolder()
                val subs = runCatching { drive.listSubfolders(root) }.getOrDefault(emptyList())
                val shoppingId = subs.firstOrNull { it.name == DriveRepository.SHOPPING_FOLDER_NAME }?.id
                val tryonsId = subs.firstOrNull { it.name == DriveRepository.TRYONS_FOLDER_NAME }?.id
                val wardrobeFolders = (closetFolderIds + listOfNotNull(shoppingId)).distinct()

                // ---- Scan: collect legacy files needing conversion ----
                val cutoutFiles = mutableListOf<Pair<String, String>>() // id, name
                val originalFiles = mutableListOf<Pair<String, String>>()
                val tryonFiles = mutableListOf<Pair<String, String>>()
                for (fid in wardrobeFolders) {
                    val files = runCatching { drive.listAllImageFiles(fid) }.getOrDefault(emptyList())
                    files.forEach { f ->
                        when {
                            f.name.endsWith(ImageEncoding.CUTOUT_SUFFIX_LEGACY) -> cutoutFiles += f.id to f.name
                            f.name.endsWith(ImageEncoding.ORIGINAL_SUFFIX_LEGACY) -> originalFiles += f.id to f.name
                        }
                    }
                }
                tryonsId?.let { tid ->
                    runCatching { drive.listAllImageFiles(tid) }.getOrDefault(emptyList()).forEach { f ->
                        if (!f.name.endsWith(".webp")) tryonFiles += f.id to f.name
                    }
                }

                val total = cutoutFiles.size + originalFiles.size + tryonFiles.size
                _progress.update { it.copy(total = total) }
                if (total == 0) return@launch

                fun bumpDone() = _progress.update { it.copy(done = it.done + 1) }

                // Maps from OLD filename -> NEW filename, used to rewrite JSON references afterwards.
                val cutoutNameMap = LinkedHashMap<String, String>()
                val tryonNameMap = LinkedHashMap<String, String>()

                // ---- Convert cutouts (alpha preserved via lossy WebP) ----
                for ((id, name) in cutoutFiles) {
                    runCatching {
                        val src = drive.cachedFile(id) ?: drive.downloadToCache(id, name)
                        ?: return@runCatching
                        val bmp = BitmapFactory.decodeFile(src.absolutePath) ?: return@runCatching
                        val tmp = File(drive.cacheDir, "${id}_conv.webp")
                        ImageEncoding.compressCutout(bmp, tmp)
                        bmp.recycle()
                        drive.updateImage(id, tmp)
                        val newName = ImageEncoding.cutoutIdFromName(name)?.let { ImageEncoding.cutoutNameFor(it) }
                            ?: (name.removeSuffix(".png") + ".webp")
                        drive.renameFile(id, newName)
                        tmp.copyTo(File(drive.cacheDir, "$id.png"), overwrite = true) // cutout display cache
                        tmp.delete()
                        cutoutNameMap[name] = newName
                    }.onFailure { Log.w(TAG, "convert cutout $id failed: ${it.message}") }
                    bumpDone()
                }

                // ---- Convert originals (archived source; resolved by ID/prefix, no JSON rewrite) ----
                for ((id, name) in originalFiles) {
                    runCatching {
                        val src = drive.cachedFile(id) ?: drive.downloadToCache(id, name)
                        ?: return@runCatching
                        val bmp = BitmapFactory.decodeFile(src.absolutePath) ?: return@runCatching
                        val tmp = File(drive.cacheDir, "${id}_conv.webp")
                        ImageEncoding.compressOriginal(bmp, tmp)
                        bmp.recycle()
                        drive.updateImage(id, tmp)
                        val newName = ImageEncoding.originalIdFromName(name)?.let { ImageEncoding.originalNameFor(it) }
                            ?: (name.removeSuffix(".jpg") + ".webp")
                        drive.renameFile(id, newName)
                        tmp.copyTo(File(drive.cacheDir, "$id.jpg"), overwrite = true)
                        ImageEncoding.originalIdFromName(name)?.let { cutId ->
                            val legacy = File(drive.cacheDir, "${cutId}_original.jpg")
                            if (legacy.exists()) tmp.copyTo(legacy, overwrite = true)
                        }
                        tmp.delete()
                    }.onFailure { Log.w(TAG, "convert original $id failed: ${it.message}") }
                    bumpDone()
                }

                // ---- Convert try-on result images (rewrite TryOn.imageName) ----
                for ((id, name) in tryonFiles) {
                    runCatching {
                        val src = drive.downloadToCache(id, name) ?: return@runCatching
                        val bmp = BitmapFactory.decodeFile(src.absolutePath) ?: return@runCatching
                        val tmp = File(drive.cacheDir, "${id}_conv.webp")
                        ImageEncoding.compressOriginal(bmp, tmp)
                        bmp.recycle()
                        drive.updateImage(id, tmp)
                        val newName = name.substringBeforeLast('.') + ".webp"
                        drive.renameFile(id, newName)
                        tmp.copyTo(File(drive.cacheDir, "tryon_$id.png"), overwrite = true) // try-on display cache
                        tmp.delete()
                        tryonNameMap[name] = newName
                    }.onFailure { Log.w(TAG, "convert try-on $id failed: ${it.message}") }
                    bumpDone()
                }

                // ---- Rewrite filename references on Drive ----
                if (cutoutNameMap.isNotEmpty()) {
                    for (fid in wardrobeFolders) {
                        runCatching {
                            val json = drive.loadOutfitsJson(fid) ?: return@runCatching
                            val outfits: List<Outfit> = gson.fromJson(json, object : TypeToken<List<Outfit>>() {}.type)
                                ?: return@runCatching
                            val updated = outfits.map { o ->
                                o.copy(itemNames = o.itemNames.map { cutoutNameMap[it] ?: it })
                            }
                            if (updated != outfits) drive.saveOutfitsJson(fid, gson.toJson(updated))
                        }.onFailure { Log.w(TAG, "rewrite outfits $fid failed: ${it.message}") }
                    }
                }
                if (cutoutNameMap.isNotEmpty() || tryonNameMap.isNotEmpty()) {
                    runCatching {
                        val json = drive.loadTryOnsJson(root) ?: return@runCatching
                        val tryons: List<TryOn> = gson.fromJson(json, object : TypeToken<List<TryOn>>() {}.type)
                            ?: return@runCatching
                        val updated = tryons.map { t ->
                            t.copy(
                                itemNames = t.itemNames.map { cutoutNameMap[it] ?: it },
                                imageName = tryonNameMap[t.imageName] ?: t.imageName,
                            )
                        }
                        if (updated != tryons) drive.saveTryOnsJson(root, gson.toJson(updated))
                    }.onFailure { Log.w(TAG, "rewrite try-ons failed: ${it.message}") }
                }

                // ---- Stamp the renamed filenames onto the store rows (the derived view follows)
                // and bump versions for Coil ----
                if (cutoutNameMap.isNotEmpty()) {
                    viewImages.forEach { img ->
                        val newName = cutoutNameMap[img.name] ?: return@forEach
                        val fid = img.folderId.ifEmpty { return@forEach }
                        runCatching { itemStore.upsert(fid, img.copy(name = newName).toCachedItem()) }
                        versions.bump(img.driveId)
                    }
                }
                // Outfit/try-on caches are intentionally left in place: their cached entries carry the
                // still-valid (stable) Drive IDs, so they keep resolving, and name-based resolution is
                // now extension-agnostic (ImageEncoding.itemMatchKey) so it survives the rename and
                // Drive's eventually-consistent listing lag without a forced reload.
            } finally {
                _progress.value = ConvertProgress()
                jobLock.release()
            }
        }
    }

    private companion object {
        const val TAG = "RepairAndSync"
    }
}
