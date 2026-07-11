package com.librelookai.wardrobe

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.local.CachedWardrobeItem
import com.librelookai.data.model.Location
import com.librelookai.data.session.ClosetSession
import com.librelookai.data.session.ClosetSessionHolder
import com.librelookai.data.session.UserPreferencesRepository
import com.librelookai.gemini.ClothingTags
import com.librelookai.service.JobLock
import com.librelookai.settings.UserPreferences
import com.librelookai.testing.FakeAiClient
import com.librelookai.testing.FakeDriveService
import com.librelookai.testing.FakeSimilarityService
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
import org.junit.Assert.assertFalse
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
 * sidecar triplet with a store-first row swap, plus — over the [FakeSimilarityService] seam —
 * the dedupe gate (pause / confirm / cancel / no-match / model-off routing) and the local-bg
 * review queue (queue / apply-prebuilt-cutout / skip / forced-unskippable / segmenter-off).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ItemIngestionPipelineTest {

    private val dispatcher = StandardTestDispatcher()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val drive = FakeDriveService()
    private val ai = FakeAiClient()
    private val items = FakeWardrobeItemStore()
    private val similarity = FakeSimilarityService()
    private val session = ClosetSessionHolder()
    private val prefs = UserPreferencesRepository()

    // Main-dispatcher scope inside — built lazily, after setMain. The pref knobs collect on
    // that scope too, so gate tests publish first, build, then advanceUntilIdle before ingest.
    private fun pipeline(driveService: FakeDriveService = drive) = ItemIngestionPipeline(
        app, driveService, ai, items, session, prefs, JobLock(app), similarity,
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

    // ---------- Dedupe gate (over the SimilarityService seam) ----------

    /** Seeds one finished item in closet f1 so the cross-closet snapshot can resolve a match. */
    private suspend fun seedWardrobeItem(driveId: String) {
        session.setClosets(listOf(Location(name = "Home", folderId = "f1")), ClosetSession.ALL_LOCATIONS_ID, "f1")
        items.replaceFolder("f1", listOf(CachedWardrobeItem(driveId = driveId, name = "${driveId}_cutout.webp", tags = null)))
        drive.cached[driveId] = File(drive.cacheDir, "$driveId.png").apply { writeText("cached") }
    }

    @Test
    fun `dedupe gate pauses a matching import for confirmation`() = runTest(dispatcher) {
        seedWardrobeItem("old-1")
        similarity.modelAvailable = true
        similarity.similarityResult = FakeSimilarityService.result("old-1")
        prefs.publish(UserPreferences(dedupeOnImport = true))

        val p = pipeline()
        advanceUntilIdle() // collect the pref knobs
        val raw = rawFile()
        p.ingest(raw, "f1", skippableLocalReview = true)
        advanceUntilIdle()

        // The index synced over the cross-closet snapshot, the query ran, and the import
        // paused for confirmation — nothing was uploaded.
        assertEquals(listOf("old-1"), similarity.syncedPools.single().map { it.driveId })
        assertEquals(listOf(raw), similarity.queries)
        val check = p.progress.value.duplicateCheck!!
        assertEquals("old-1", check.matches.single().image.driveId)
        assertEquals(raw.absolutePath, check.rawFilePath)
        assertTrue(drive.uploadedImages.isEmpty())
        assertFalse(p.progress.value.isUploading)
    }

    @Test
    fun `confirmDuplicateImport resumes the stashed routing into a full upload`() = runTest(dispatcher) {
        seedWardrobeItem("old-1")
        similarity.modelAvailable = true
        similarity.similarityResult = FakeSimilarityService.result("old-1")
        prefs.publish(UserPreferences(dedupeOnImport = true))

        val p = pipeline()
        advanceUntilIdle()
        p.ingest(rawFile(), "f1", skippableLocalReview = true)
        advanceUntilIdle()
        p.confirmDuplicateImport()
        advanceUntilIdle()

        // Raw + (degraded) cutout + original — the regular funnel ran to completion.
        assertNull(p.progress.value.duplicateCheck)
        assertEquals(3, drive.uploadedImages.size)
        assertNotNull(items.find("upload-2"))
    }

    @Test
    fun `cancelDuplicateImport discards the raw file and uploads nothing`() = runTest(dispatcher) {
        seedWardrobeItem("old-1")
        similarity.modelAvailable = true
        similarity.similarityResult = FakeSimilarityService.result("old-1")
        prefs.publish(UserPreferences(dedupeOnImport = true))

        val p = pipeline()
        advanceUntilIdle()
        val raw = rawFile()
        p.ingest(raw, "f1", skippableLocalReview = true)
        advanceUntilIdle()
        p.cancelDuplicateImport()
        advanceUntilIdle()

        assertNull(p.progress.value.duplicateCheck)
        assertFalse(raw.exists())
        assertTrue(drive.uploadedImages.isEmpty())
    }

    @Test
    fun `a match outside the cross-closet snapshot proceeds straight to upload`() = runTest(dispatcher) {
        seedWardrobeItem("old-1")
        similarity.modelAvailable = true
        // The scripted match points at an id the snapshot can't resolve (e.g. a row whose
        // local cache is gone) — the gate must not pause on a match it can't show.
        similarity.similarityResult = FakeSimilarityService.result("ghost")
        prefs.publish(UserPreferences(dedupeOnImport = true))

        val p = pipeline()
        advanceUntilIdle()
        p.ingest(rawFile(), "f1", skippableLocalReview = true)
        advanceUntilIdle()

        assertNull(p.progress.value.duplicateCheck)
        assertEquals(3, drive.uploadedImages.size)
    }

    @Test
    fun `an unavailable model skips the dedupe gate entirely`() = runTest(dispatcher) {
        prefs.publish(UserPreferences(dedupeOnImport = true)) // model stays off

        val p = pipeline()
        advanceUntilIdle()
        p.ingest(rawFile(), "f1", skippableLocalReview = true)
        advanceUntilIdle()

        assertTrue(similarity.queries.isEmpty())
        assertEquals(3, drive.uploadedImages.size)
    }

    // ---------- Local background-removal review (over the SimilarityService seam) ----------

    @Test
    fun `preferLocalBgRemoval queues the import for review instead of uploading`() = runTest(dispatcher) {
        similarity.segmenterAvailable = true
        prefs.publish(UserPreferences(dedupeOnImport = false, preferLocalBgRemoval = true))

        val p = pipeline()
        advanceUntilIdle()
        val raw = rawFile()
        p.ingest(raw, "f1", skippableLocalReview = true)
        advanceUntilIdle()

        val head = p.progress.value.localBgReviewQueue.single()
        assertEquals(raw.absolutePath, head.rawFilePath)
        assertTrue(head.skippable)
        assertTrue(drive.uploadedImages.isEmpty())
    }

    @Test
    fun `applyLocalBgCutout hands the on-device cutout to the worker with no Gemini bg call`() = runTest(dispatcher) {
        similarity.segmenterAvailable = true
        prefs.publish(UserPreferences(dedupeOnImport = false, preferLocalBgRemoval = true))

        val p = pipeline()
        advanceUntilIdle()
        p.ingest(rawFile(), "f1", skippableLocalReview = true)
        advanceUntilIdle()
        val cutout = File(drive.cacheDir, "segmenter_out.png").apply { writeText("local-cutout") }
        p.applyLocalBgCutout(cutout)
        advanceUntilIdle()

        // The worker uploaded the stashed local cutout (stable per-job name) instead of
        // asking Gemini, and the finished row is live.
        assertTrue(p.progress.value.localBgReviewQueue.isEmpty())
        assertTrue(ai.removedBackgrounds.isEmpty())
        assertEquals("upload-1_local_cutout.png", drive.uploadedImages[1].second)
        assertNotNull(items.find("upload-2"))
        assertEquals(0, p.progress.value.pendingJobs)
    }

    @Test
    fun `skipLocalBgReview falls back to the Gemini path`() = runTest(dispatcher) {
        similarity.segmenterAvailable = true
        prefs.publish(UserPreferences(dedupeOnImport = false, preferLocalBgRemoval = true))

        val p = pipeline()
        advanceUntilIdle()
        p.ingest(rawFile(), "f1", skippableLocalReview = true)
        advanceUntilIdle()
        p.skipLocalBgReview()
        advanceUntilIdle()

        assertTrue(p.progress.value.localBgReviewQueue.isEmpty())
        assertEquals(1, ai.removedBackgrounds.size)
        assertEquals(3, drive.uploadedImages.size)
    }

    @Test
    fun `a forced review ignores the preference and cannot be skipped`() = runTest(dispatcher) {
        similarity.segmenterAvailable = true
        prefs.publish(UserPreferences(dedupeOnImport = false, preferLocalBgRemoval = false))

        val p = pipeline()
        advanceUntilIdle()
        val raw = rawFile()
        // The URL-import shape: review mandatory even with the preference off.
        p.ingest(raw, "f1", skippableLocalReview = false, forceLocalReview = true)
        advanceUntilIdle()

        val head = p.progress.value.localBgReviewQueue.single()
        assertFalse(head.skippable)
        p.skipLocalBgReview() // no-op for an unskippable entry
        advanceUntilIdle()
        assertEquals(1, p.progress.value.localBgReviewQueue.size)

        p.cancelLocalBgReview()
        advanceUntilIdle()
        assertTrue(p.progress.value.localBgReviewQueue.isEmpty())
        assertFalse(raw.exists())
        assertTrue(drive.uploadedImages.isEmpty())
    }

    @Test
    fun `an unavailable segmenter uploads directly even when review is preferred`() = runTest(dispatcher) {
        prefs.publish(UserPreferences(dedupeOnImport = false, preferLocalBgRemoval = true))

        val p = pipeline() // segmenterAvailable stays false
        advanceUntilIdle()
        p.ingest(rawFile(), "f1", skippableLocalReview = true)
        advanceUntilIdle()

        assertTrue(p.progress.value.localBgReviewQueue.isEmpty())
        assertEquals(3, drive.uploadedImages.size)
    }
}
