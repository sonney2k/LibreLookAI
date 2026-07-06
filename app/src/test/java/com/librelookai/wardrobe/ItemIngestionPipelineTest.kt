package com.librelookai.wardrobe

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.session.ClosetSessionHolder
import com.librelookai.data.session.UserPreferencesRepository
import com.librelookai.gemini.ClothingTags
import com.librelookai.service.JobLock
import com.librelookai.testing.FakeAiClient
import com.librelookai.testing.FakeDriveService
import com.librelookai.testing.FakeWardrobeItemStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fake-based suite for the photo-ingestion pipeline (refactor § 8 — the § 5 slice 5 use-case):
 * the raw-upload → serial-worker funnel that turns a photo into the cutout + original +
 * sidecar triplet with a store-first row swap. The dedupe gate and the local-bg review queue
 * are exercised only at their default-off routing — both branches require the on-device
 * embedder/segmenter (`EmbeddingService` is a static object, not yet behind a seam).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ItemIngestionPipelineTest {

    private val dispatcher = StandardTestDispatcher()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val drive = FakeDriveService()
    private val ai = FakeAiClient()
    private val items = FakeWardrobeItemStore()

    // Main-dispatcher scope inside — built lazily, after setMain.
    private fun pipeline(driveService: FakeDriveService = drive) = ItemIngestionPipeline(
        app, driveService, ai, items, ClosetSessionHolder(), UserPreferencesRepository(), JobLock(app),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun rawFile(name: String = "raw.jpg"): File =
        File(drive.cacheDir, name).apply { writeText("raw-bytes") }

    @Test
    fun `ingest uploads the raw, swaps in the finished cutout row and stamps the sidecar`() = runTest(dispatcher) {
        ai.removeBackgroundResult = File(drive.cacheDir, "processed.png").apply { writeText("cutout-bytes") }
        ai.classifyResult = ClothingTags(label = "Red dress", type = "dress")

        val p = pipeline()
        p.ingest(rawFile(), "f1", skippableLocalReview = true)
        advanceUntilIdle()

        // Raw upload (upload-1) → cutout upload (upload-2, renamed) → original (upload-3,
        // named after the cutout id); the temporary raw Drive file is deleted afterwards.
        assertEquals(
            listOf("f1" to "raw.jpg", "f1" to "processed.png", "f1" to "upload-2${DriveRepository.ORIGINAL_SUFFIX}"),
            drive.uploadedImages,
        )
        assertEquals(listOf("upload-2" to "upload-2${DriveRepository.CUTOUT_SUFFIX}"), drive.renamedFiles)
        assertEquals(listOf("upload-1"), drive.deletedFileIds)

        // The raw store row was swapped for the finished cutout entry (staleDriveId path).
        assertNull(items.find("upload-1"))
        val (folder, row) = items.find("upload-2")!!
        assertEquals("f1", folder)
        assertEquals("dress", row.tags?.type)
        assertEquals("upload-3", row.originalDriveId)
        assertNotNull(row.sidecarDriveId)

        // Sidecar carries the tags; worker drained cleanly.
        val sidecar = drive.upsertedSidecars["f1/upload-2${DriveRepository.SIDECAR_SUFFIX}"]
        assertTrue(sidecar!!.contains("dress"))
        assertEquals(0, p.progress.value.pendingJobs)
        assertNull(p.progress.value.processingImageId)

        // Local caches for the finished item: display cutout + archived original.
        assertTrue(File(drive.cacheDir, "upload-2.png").exists())
        assertTrue(File(drive.cacheDir, "upload-2_original.jpg").exists())
    }

    @Test
    fun `a failed bg removal degrades to the raw bytes and a null-tags sidecar`() = runTest(dispatcher) {
        // FakeAiClient defaults: removeBackground and classifyClothing both fail.
        val p = pipeline()
        p.ingest(rawFile(), "f1", skippableLocalReview = true)
        advanceUntilIdle()

        // The raw file itself is uploaded as the cutout (graceful degrade), tags stay null,
        // and the sidecar is still written so the item is discoverable on the next load
        // (Gson omits the null tags field — presence of the file is the invariant).
        val (_, row) = items.find("upload-2")!!
        assertNull(row.tags)
        val sidecar = drive.upsertedSidecars["f1/upload-2${DriveRepository.SIDECAR_SUFFIX}"]
        assertNotNull(sidecar)
        assertTrue(sidecar!!.contains("upload-3")) // the archived original's id still rides along
        assertEquals(0, p.progress.value.pendingJobs)
    }

    @Test
    fun `a failed raw upload surfaces the error and never enqueues a job`() = runTest(dispatcher) {
        val failingDrive = object : FakeDriveService() {
            override suspend fun uploadImage(folderId: String, imageFile: File) =
                throw IllegalStateException("boom")
        }

        val p = pipeline(failingDrive)
        val errors = mutableListOf<String?>()
        val collector = launch { p.errors.collect { errors += it } }
        p.ingest(File(failingDrive.cacheDir, "raw.jpg").apply { writeText("raw") }, "f1", skippableLocalReview = true)
        advanceUntilIdle()
        collector.cancel()

        assertEquals("boom", errors.last())
        assertEquals(0, p.progress.value.pendingJobs)
        assertTrue(p.progress.value.isUploading.not())
        assertTrue(items.find("upload-1") == null)
    }

    @Test
    fun `gallery batch uploads every uri and drains the worker for each`() = runTest(dispatcher) {
        ai.removeBackgroundResult = File(drive.cacheDir, "processed.png").apply { writeText("cutout") }
        val uris = listOf("a.jpg", "b.jpg").map { name ->
            android.net.Uri.fromFile(File(drive.cacheDir, "src_$name").apply { writeText("img-$name") })
        }

        val p = pipeline()
        p.uploadGalleryPhotos(uris, "f1")
        advanceUntilIdle()

        // Two raw uploads + two cutout uploads + two originals; both finished rows live.
        assertEquals(6, drive.uploadedImages.size)
        assertEquals(2, items.itemsFor("f1").size)
        assertEquals(0, p.progress.value.pendingJobs)
        assertEquals(0, p.progress.value.batchTotal)
    }
}
