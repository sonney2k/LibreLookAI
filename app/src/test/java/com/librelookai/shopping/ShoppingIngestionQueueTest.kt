package com.librelookai.shopping

import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.local.CachedWardrobeItem
import com.librelookai.data.session.UserPreferencesRepository
import com.librelookai.gemini.ClothingTags
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

/**
 * Fake-based suite for the shopping wishlist's serial upload worker (refactor § 8 — the
 * § 5 slice 9 extraction off [ShoppingClosetViewModel]): a queued raw upload becomes the
 * cutout + original + sidecar triplet, the store row swap keys off the job's owning folder,
 * and worker failures reach the error funnel without stalling the queue. Plain JVM — none of
 * the collaborators touch Android.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingIngestionQueueTest {

    private val dispatcher = StandardTestDispatcher()
    private val drive = FakeDriveService()
    private val ai = FakeAiClient()
    private val items = FakeWardrobeItemStore()

    // Main-dispatcher scope inside — built lazily, after setMain.
    private fun queue(driveService: FakeDriveService = drive) =
        ShoppingIngestionQueue(driveService, ai, items, UserPreferencesRepository())

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Stages the raw upload the VM's uploadRaw would have left behind: cache file + store row. */
    private suspend fun stageRaw(driveId: String, folderId: String) {
        File(drive.cacheDir, "${driveId}_original.jpg").writeText("raw-bytes")
        items.addAll(folderId, listOf(CachedWardrobeItem(driveId = driveId, name = "$driveId.jpg", tags = null)))
    }

    @Test
    fun `a queued raw becomes the finished cutout row with sidecar and original`() = runTest(dispatcher) {
        stageRaw("raw1", "shopping-folder")
        ai.removeBackgroundResult = File(drive.cacheDir, "processed.png").apply { writeText("cutout") }
        ai.classifyResult = ClothingTags(label = "Leather bag", type = "bag")

        val q = queue()
        q.enqueue("raw1", "shopping-folder")
        advanceUntilIdle()

        // Cutout uploads then renames to its own id; the original is named after the cutout id.
        assertEquals(
            listOf(
                "shopping-folder" to "processed.png",
                "shopping-folder" to "upload-1${DriveRepository.ORIGINAL_SUFFIX}",
            ),
            drive.uploadedImages,
        )
        assertEquals(listOf("upload-1" to "upload-1${DriveRepository.CUTOUT_SUFFIX}"), drive.renamedFiles)
        assertEquals(listOf("raw1"), drive.deletedFileIds)

        // The raw row was swapped for the finished cutout entry under the job's folder.
        assertNull(items.find("raw1"))
        val (folder, row) = items.find("upload-1")!!
        assertEquals("shopping-folder", folder)
        assertEquals("bag", row.tags?.type)
        assertEquals("upload-2", row.originalDriveId)
        assertNotNull(row.sidecarDriveId)
        assertNotNull(drive.upsertedSidecars["shopping-folder/upload-1${DriveRepository.SIDECAR_SUFFIX}"])
        assertEquals(0, q.pendingJobs.value)
    }

    @Test
    fun `a missing raw cache file drops the job without touching Drive`() = runTest(dispatcher) {
        val q = queue()
        q.enqueue("ghost", "shopping-folder")
        advanceUntilIdle()

        assertTrue(drive.uploadedImages.isEmpty())
        assertEquals(0, q.pendingJobs.value)
    }

    @Test
    fun `a failed cutout upload drops the job silently and the queue keeps draining`() = runTest(dispatcher) {
        // The cutout upload is runCatching-swallowed inside process(): the job is dropped, the
        // raw placeholder row stays (retryable on a later reload), and no error is emitted —
        // the funnel only carries exceptions that escape process() entirely.
        val failingOnce = object : FakeDriveService() {
            var failures = 1
            override suspend fun uploadImage(folderId: String, imageFile: File): com.librelookai.data.drive.DriveFileDto {
                if (failures-- > 0) throw IllegalStateException("boom")
                return super.uploadImage(folderId, imageFile)
            }
        }
        File(failingOnce.cacheDir, "raw1_original.jpg").writeText("raw")
        File(failingOnce.cacheDir, "raw2_original.jpg").writeText("raw")
        items.addAll("shopping-folder", listOf(
            CachedWardrobeItem(driveId = "raw1", name = "raw1.jpg", tags = null),
            CachedWardrobeItem(driveId = "raw2", name = "raw2.jpg", tags = null),
        ))

        val q = queue(failingOnce)
        val errors = mutableListOf<String>()
        val collector = launch { q.errors.collect { errors += it } }
        q.enqueue("raw1", "shopping-folder")
        q.enqueue("raw2", "shopping-folder")
        advanceUntilIdle()
        collector.cancel()

        assertEquals(emptyList<String>(), errors)
        assertNotNull(items.find("raw1")) // placeholder row survives the dropped job
        assertNotNull(items.find("upload-1")) // the second job still completed
        assertNull(items.find("raw2"))
        assertEquals(0, q.pendingJobs.value)
    }
}
