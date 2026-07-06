package com.librelookai.wardrobe

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.librelookai.data.drive.DriveFileDto
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.TryOn
import com.librelookai.service.JobLock
import com.librelookai.testing.FakeDriveService
import com.librelookai.testing.FakeWardrobeItemStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fake-based suite for the one-shot WebP conversion maintenance op (refactor § 8 — the § 5
 * slice 6 use-case): the legacy-file scan across closets + `_shopping` + `_tryons`, the
 * in-place re-encode (stable Drive IDs) + `.webp` rename, the rewrite of every persisted
 * *filename* reference (`Outfit.itemNames`, `TryOn.itemNames`/`imageName`), the store stamp
 * for the currently-displayed rows, and idempotency (a rerun over converted files is a no-op).
 * Re-encodes run for real over tiny Robolectric-native bitmaps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WebpConvertUseCaseTest {

    private val dispatcher = StandardTestDispatcher()
    private val gson = Gson()

    // Serve registered cache entries from downloadToCache too — the try-on loop downloads
    // directly (result images are not display-cached under their Drive id).
    private val drive = object : FakeDriveService() {
        override suspend fun downloadToCache(driveId: String, driveName: String): File? =
            cached[driveId] ?: super.downloadToCache(driveId, driveName)
    }
    private val items = FakeWardrobeItemStore()
    private val versions = ItemVersions()

    private fun useCase() = WebpConvertUseCase(
        drive, items, versions, JobLock(ApplicationProvider.getApplicationContext()),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The use-case runs on the real [Dispatchers.IO] — same wait as the sibling suites. */
    private suspend fun awaitUntil(condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            val deadline = System.currentTimeMillis() + 5_000
            while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        }
    }

    /** Registers a real decodable PNG (extension in [driveId]'s Drive name is irrelevant —
     *  decode sniffs bytes) in the fake's cache under [driveId]. */
    private fun cachedBitmap(driveId: String) {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.RED)
        val f = File(drive.cacheDir, "src-$driveId.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        drive.cached[driveId] = f
    }

    private fun subfolders(vararg named: Pair<String, String>) {
        drive.subfoldersByFolder[drive.rootFolderId] =
            named.map { (id, name) -> DriveFileDto(id = id, name = name) }
    }

    @Test
    fun `converts legacy files across closets, shopping and tryons, and rewrites every filename reference`() = runTest(dispatcher) {
        subfolders(
            "shop" to DriveRepository.SHOPPING_FOLDER_NAME,
            "tryons" to DriveRepository.TRYONS_FOLDER_NAME,
        )
        // f1: one legacy cutout + its legacy original + one already-converted item (skipped).
        drive.allImageFilesByFolder["f1"] = listOf(
            DriveFileDto(id = "c1", name = "c1_cutout.png"),
            DriveFileDto(id = "o1", name = "c1_original.jpg"),
            DriveFileDto(id = "w1", name = "w1_cutout.webp"),
        )
        // The shopping closet is scanned too.
        drive.allImageFilesByFolder["shop"] = listOf(
            DriveFileDto(id = "s1", name = "s1_cutout.png"),
        )
        // A try-on result image (anything not .webp in _tryons).
        drive.allImageFilesByFolder["tryons"] = listOf(
            DriveFileDto(id = "t1", name = "tryon_res.png"),
        )
        listOf("c1", "o1", "s1", "t1").forEach { cachedBitmap(it) }

        drive.outfitsJsonByFolder["f1"] = gson.toJson(
            listOf(Outfit(id = "outfit1", itemNames = listOf("c1_cutout.png", "w1_cutout.webp"))),
        )
        drive.tryOnsJsonByRoot[drive.rootFolderId] = gson.toJson(
            listOf(TryOn(id = "t", imageName = "tryon_res.png", itemNames = listOf("c1_cutout.png"))),
        )

        val viewImages = listOf(
            DriveImage(driveId = "c1", localPath = "", name = "c1_cutout.png", folderId = "f1"),
            // All-locations rows carry no owning folder — never stamped (cache-folder rule).
            DriveImage(driveId = "s1", localPath = "", name = "s1_cutout.png", folderId = ""),
        )

        val uc = useCase()
        uc.start(listOf("f1"), viewImages)
        assertTrue(uc.progress.value.isConverting)
        awaitUntil { !uc.progress.value.isConverting }

        // Bytes replaced in place (stable IDs), files renamed to .webp.
        assertEquals(listOf("c1", "s1", "o1", "t1"), drive.updatedImageIds)
        assertEquals(
            listOf(
                "c1" to "c1_cutout.webp",
                "s1" to "s1_cutout.webp",
                "o1" to "c1_original.webp",
                "t1" to "tryon_res.webp",
            ),
            drive.renamedFiles,
        )

        // Persisted filename references follow; already-webp names pass through untouched.
        val outfits: List<Outfit> = gson.fromJson(
            drive.outfitsJsonByFolder["f1"],
            com.google.gson.reflect.TypeToken.getParameterized(List::class.java, Outfit::class.java).type,
        )
        assertEquals(listOf("c1_cutout.webp", "w1_cutout.webp"), outfits.single().itemNames)
        val tryOns: List<TryOn> = gson.fromJson(
            drive.tryOnsJsonByRoot[drive.rootFolderId],
            com.google.gson.reflect.TypeToken.getParameterized(List::class.java, TryOn::class.java).type,
        )
        assertEquals("tryon_res.webp", tryOns.single().imageName)
        assertEquals(listOf("c1_cutout.webp"), tryOns.single().itemNames)

        // Only the displayed row with an owning folder is stamped with the new name.
        assertEquals("c1_cutout.webp", items.find("c1")!!.second.name)
        assertTrue(items.find("s1") == null)
        assertTrue("c1" in versions.versions.value)

        // Display caches were refreshed for the converted files.
        assertTrue(File(drive.cacheDir, "c1.png").exists()) // cutout display cache
        assertTrue(File(drive.cacheDir, "o1.jpg").exists()) // original archive cache
        assertTrue(File(drive.cacheDir, "tryon_t1.png").exists()) // try-on display cache

        assertEquals(0, uc.progress.value.total) // progress cleared when done
    }

    @Test
    fun `a fully converted wardrobe is a no-op`() = runTest(dispatcher) {
        subfolders("tryons" to DriveRepository.TRYONS_FOLDER_NAME)
        drive.allImageFilesByFolder["f1"] = listOf(
            DriveFileDto(id = "w1", name = "w1_cutout.webp"),
            DriveFileDto(id = "w2", name = "w1_original.webp"),
        )
        drive.allImageFilesByFolder["tryons"] = listOf(
            DriveFileDto(id = "t1", name = "tryon_res.webp"),
        )
        drive.outfitsJsonByFolder["f1"] = gson.toJson(listOf(Outfit(id = "o", itemNames = listOf("w1_cutout.webp"))))
        val before = drive.outfitsJsonByFolder["f1"]

        val uc = useCase()
        uc.start(listOf("f1"), emptyList())
        awaitUntil { !uc.progress.value.isConverting }

        assertTrue(drive.updatedImageIds.isEmpty())
        assertTrue(drive.renamedFiles.isEmpty())
        assertEquals(before, drive.outfitsJsonByFolder["f1"]) // no rewrite pass ran
        assertTrue(versions.versions.value.isEmpty())
    }

    @Test
    fun `an unfetchable file is skipped without renaming or rewriting references`() = runTest(dispatcher) {
        val failingDrive = object : FakeDriveService() {
            override suspend fun downloadToCache(driveId: String, driveName: String): File? = null
        }
        failingDrive.allImageFilesByFolder["f1"] = listOf(
            DriveFileDto(id = "c1", name = "c1_cutout.png"),
        )
        failingDrive.outfitsJsonByFolder["f1"] =
            gson.toJson(listOf(Outfit(id = "o", itemNames = listOf("c1_cutout.png"))))
        val before = failingDrive.outfitsJsonByFolder["f1"]

        val uc = WebpConvertUseCase(
            failingDrive, items, versions, JobLock(ApplicationProvider.getApplicationContext()),
        )
        uc.start(listOf("f1"), emptyList())
        awaitUntil { !uc.progress.value.isConverting }

        // Fetch failed → no upload, no rename — and crucially no reference rewrite, so the
        // still-.png Drive file keeps resolving.
        assertTrue(failingDrive.updatedImageIds.isEmpty())
        assertTrue(failingDrive.renamedFiles.isEmpty())
        assertEquals(before, failingDrive.outfitsJsonByFolder["f1"])
    }

    @Test
    fun `a second start while converting is ignored`() = runTest(dispatcher) {
        // Gate the subfolder listing so the first run is still in flight when the second starts.
        val release = java.util.concurrent.atomic.AtomicBoolean(false)
        val listSubfolderCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val gatedDrive = object : FakeDriveService() {
            override suspend fun listSubfolders(parentFolderId: String): List<DriveFileDto> {
                listSubfolderCalls.incrementAndGet()
                withContext(Dispatchers.Default) { while (!release.get()) Thread.sleep(5) }
                return emptyList()
            }
        }
        val uc = WebpConvertUseCase(
            gatedDrive, items, versions, JobLock(ApplicationProvider.getApplicationContext()),
        )

        uc.start(listOf("f1"), emptyList())
        assertTrue(uc.progress.value.isConverting)
        uc.start(listOf("f1"), emptyList()) // re-entry guard
        release.set(true)
        awaitUntil { !uc.progress.value.isConverting }

        assertEquals(1, listSubfolderCalls.get()) // the second start never launched a run
        assertFalse(uc.progress.value.isConverting)
    }
}
