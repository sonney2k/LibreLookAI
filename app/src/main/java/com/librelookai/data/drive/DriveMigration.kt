package com.librelookai.data.drive

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val FOLDER_MIME = "application/vnd.google-apps.folder"
private const val MIGRATION_TAG = "DriveMigration"
private const val COPY_CONCURRENCY = 8

/**
 * Metadata JSONs that are safe to blanket-remap (old Drive ID → new) — they reference IDs as bare
 * values (closet folderIds, profile-photo IDs) and contain NO filenames that embed an ID. Files
 * that DO embed IDs in filenames (`_outfits.json` / `_tryons.json` carry item `*_cutout.png`
 * filenames) must NOT be blanket-replaced — see [remapMetadataIds].
 */
private val BLANKET_REMAP_NAMES = setOf(
    DriveRepository.LOCATIONS_FILE_NAME,   // closet folderIds
    DriveRepository.PREFERENCES_FILE_NAME, // profile photo driveIds per slot
)

/**
 * One-time migration for accounts whose data was created under a *different* project's OAuth client
 * — invisible to this app's scope. Server-side-copies the entire [legacyFolderId] tree into a fresh,
 * app-owned root folder so the copies are created by THIS project (`923211051414`) and become
 * readable under `drive.file`. Persists the new folder as the active root and renames the legacy
 * folder so the two stay clearly separate.
 *
 * **Identity remapping.** Copies get NEW Drive IDs, which breaks every stored reference to an old
 * ID. So we build a complete `oldId → newId` map (files *and* folders) during the copy, then:
 *   - re-key each item's **sidecar** to `{newCutoutId}.json` (cutouts are matched to sidecars by
 *     `sidecar.name == "{cutout.driveId}.json"`), keeping the cutout filename so outfits still
 *     resolve items by name; and
 *   - blanket-rewrite the ID references inside the metadata JSONs ([METADATA_JSON_NAMES] + per-trip
 *     files): closet folderIds, try-on `imageDriveId`, profile-photo IDs, trip `packedItemIds`, etc.
 *
 * Requires full `drive` to read the legacy files; production ships `drive.file`. Returns new root ID.
 */
suspend fun DriveRepository.migrateLegacyInto(
    legacyFolderId: String,
    onProgress: (copied: Int, total: Int) -> Unit = { _, _ -> },
): String {
    val total = countFilesRecursive(legacyFolderId)
    val newRoot = createRootFolderForced("LibreLookAI")
    Log.d(MIGRATION_TAG, "migrating $total files from $legacyFolderId into new root $newRoot")

    val sem = Semaphore(COPY_CONCURRENCY)
    val done = AtomicInteger(0)
    // oldId -> newId for EVERY copied file and folder, used to rewrite stored references.
    val idMap = ConcurrentHashMap<String, String>().apply { put(legacyFolderId, newRoot) }
    fun tick() = onProgress(done.incrementAndGet(), total)

    coroutineScope {
        suspend fun migrate(srcId: String, dstId: String) {
            val children = listAllChildren(srcId)
            val byName = children.associateBy { it.name }
            val handled = HashSet<String>()
            val jobs = ArrayList<Deferred<*>>()

            // 1. Subfolders → recurse.
            for (f in children.filter { it.mimeType == FOLDER_MIME }) {
                handled += f.name
                val sub = createSubfolder(dstId, f.name)
                idMap[f.id] = sub
                jobs += async { migrate(f.id, sub) }
            }

            // 2. Item triplets, anchored on the cutout so the sidecar can be re-keyed to the new ID.
            for (cut in children.filter { it.name.endsWith(DriveRepository.CUTOUT_SUFFIX) }) {
                val base = cut.name.removeSuffix(DriveRepository.CUTOUT_SUFFIX)
                val orig = byName["$base${DriveRepository.ORIGINAL_SUFFIX}"]
                val side = byName["$base${DriveRepository.SIDECAR_SUFFIX}"]
                handled += cut.name
                orig?.let { handled += it.name }
                side?.let { handled += it.name }
                jobs += async {
                    val newCut = sem.withPermit {
                        copyFile(cut.id, cut.name, dstId, cut.createdTime, cut.modifiedTime, cut.appProperties)
                    }.also { tick() }
                    if (newCut != null) idMap[cut.id] = newCut
                    val newOrig = orig?.let { o ->
                        sem.withPermit {
                            copyFile(o.id, o.name, dstId, o.createdTime, o.modifiedTime, o.appProperties)
                        }.also { tick() }?.also { idMap[o.id] = it }
                    }
                    if (side != null) {
                        val rewritten = rewriteSidecar(side.id, newOrig)
                        val sideName = (newCut ?: base) + DriveRepository.SIDECAR_SUFFIX
                        sem.withPermit { uploadTextFile(dstId, sideName, "application/json", rewritten) }
                        tick()
                    }
                }
            }

            // 3. Everything else (system JSONs, try-on images, profile photos, orphans) → copy verbatim.
            for (c in children) {
                if (c.mimeType == FOLDER_MIME || c.name in handled) continue
                jobs += async {
                    sem.withPermit {
                        copyFile(c.id, c.name, dstId, c.createdTime, c.modifiedTime, c.appProperties)
                    }.also { tick() }?.let { idMap[c.id] = it }
                }
            }
            jobs.awaitAll()
        }
        migrate(legacyFolderId, newRoot)
    }

    // Rewrite stored Drive-ID references in the metadata JSONs now that we have the full map.
    runCatching { remapMetadataIds(newRoot, idMap) }
        .onFailure { Log.w(MIGRATION_TAG, "metadata id remap failed: ${it.message}") }

    setPickedRootFolder(newRoot)
    runCatching { renameFile(legacyFolderId, "LibreLookAI (old)") }
        .onFailure { Log.w(MIGRATION_TAG, "rename legacy failed: ${it.message}") }
    Log.d(MIGRATION_TAG, "migration done: $done/$total into $newRoot")
    return newRoot
}

/**
 * Rewrites stored Drive-ID references after the copy. Surgical on purpose:
 * - [BLANKET_REMAP_NAMES] + per-trip JSONs (packedItemIds) → blanket replace (no embedded-ID filenames).
 * - `_tryons.json` → only `imageDriveId` per entry (its `itemNames`/`imageName` embed old IDs — leave them).
 * - `_outfits.json` / `_outfit_events.json` → untouched (outfits resolve items by filename, preserved).
 */
private suspend fun DriveRepository.remapMetadataIds(newRoot: String, idMap: Map<String, String>) {
    val rootChildren = listAllChildren(newRoot)

    suspend fun blanketRemap(fileId: String) {
        val content = downloadFileText(fileId, token()) ?: return
        var out = content
        for ((old, new) in idMap) if (out.contains(old)) out = out.replace(old, new)
        if (out != content) overwriteFileText(fileId, "application/json", out)
    }

    rootChildren.filter { it.name in BLANKET_REMAP_NAMES }.forEach { blanketRemap(it.id) }
    // Per-trip JSONs carry packedItemIds (cutout IDs) and no filenames → blanket-safe.
    rootChildren.firstOrNull { it.mimeType == FOLDER_MIME && it.name == "_trips" }?.let { trips ->
        listAllChildren(trips.id).filter { it.name.endsWith(".json") }.forEach { blanketRemap(it.id) }
    }
    // _tryons.json: remap only the generated-image IDs; never the embedded item filenames.
    rootChildren.firstOrNull { it.name == DriveRepository.TRYONS_FILE_NAME }?.let { tryons ->
        val content = downloadFileText(tryons.id, token())
        val out = content?.let {
            runCatching {
                val arr = JsonParser.parseString(it).asJsonArray
                for (el in arr) {
                    val o = el.asJsonObject
                    val old = o.get("imageDriveId")?.takeIf { e -> !e.isJsonNull }?.asString
                    if (old != null && idMap.containsKey(old)) o.addProperty("imageDriveId", idMap[old])
                }
                gson.toJson(arr)
            }.getOrNull()
        }
        if (out != null && out != content) overwriteFileText(tryons.id, "application/json", out)
    }
}

/** Copies a sidecar's JSON, repointing `originalDriveId` at the freshly-copied original. */
private suspend fun DriveRepository.rewriteSidecar(sidecarId: String, newOriginalId: String?): String {
    val content = downloadFileText(sidecarId, token()) ?: return "{}"
    return runCatching {
        val obj = JsonParser.parseString(content).asJsonObject
        if (newOriginalId != null) obj.addProperty("originalDriveId", newOriginalId) else obj.remove("originalDriveId")
        gson.toJson(obj)
    }.getOrDefault(content)
}

private suspend fun DriveRepository.countFilesRecursive(folderId: String): Int {
    var n = 0
    for (child in listAllChildren(folderId)) {
        n += if (child.mimeType == FOLDER_MIME) countFilesRecursive(child.id) else 1
    }
    return n
}
