package com.librelookai.wardrobe

import androidx.test.core.app.ApplicationProvider
import com.librelookai.data.drive.DrainScheduler
import com.librelookai.data.drive.SyncEngine
import com.librelookai.data.local.CachedWardrobeItem
import com.librelookai.data.local.PendingMutation
import com.librelookai.data.local.PendingMutationStore
import com.librelookai.data.session.UserPreferencesRepository
import com.librelookai.gemini.ClothingTags
import com.librelookai.service.JobLock
import com.librelookai.testing.FakeAiClient
import com.librelookai.testing.FakeDriveService
import com.librelookai.testing.FakeWardrobeItemStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The first fake-based tests over the [com.librelookai.gemini.AiClient] seam (refactor § 3
 * slice 4 / § 8): the bulk maintenance use-cases run against [FakeAiClient] +
 * [FakeDriveService], so their store-first write rules and the credits-exhausted abort become
 * tested invariants. Robolectric only because [JobLock] touches Android services; the fakes
 * themselves are plain in-memory objects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WardrobeBulkAiUseCasesTest {

    private class FakeMutationStore : PendingMutationStore {
        val rows = mutableListOf<PendingMutation>()
        private var nextId = 1L
        override suspend fun enqueue(
            kind: String,
            targetId: String,
            folderId: String?,
            payload: String,
            rollback: String?,
        ): Long {
            val id = nextId++
            rows += PendingMutation(id, kind, targetId, folderId, payload, rollback, 0, null, id)
            return id
        }

        override suspend fun oldest(): PendingMutation? = rows.minByOrNull { it.id }
        override suspend fun all(): List<PendingMutation> = rows.sortedBy { it.id }
        override suspend fun remove(id: Long) {
            rows.removeAll { it.id == id }
        }

        override suspend fun recordFailure(id: Long, error: String) {}
        override suspend fun count(): Int = rows.size
    }

    /** [FakeDriveService] whose local-cache lookup is scriptable per drive id. */
    private class CachingFakeDrive : FakeDriveService() {
        val cached = mutableMapOf<String, File>()
        override fun cachedFile(driveId: String): File? = cached[driveId]
    }

    private val dispatcher = StandardTestDispatcher()
    private val drive = CachingFakeDrive()
    private val ai = FakeAiClient()
    private val items = FakeWardrobeItemStore()
    private val mutations = FakeMutationStore()
    private val engine = SyncEngine(mutations, emptySet(), object : DrainScheduler {
        override fun ensureScheduled() {}
    })
    private val sidecarSync = SidecarSyncQueue(items, mutations, engine)
    private val jobLock = JobLock(ApplicationProvider.getApplicationContext())
    private val prefsRepo = UserPreferencesRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun row(driveId: String) = CachedWardrobeItem(
        driveId = driveId,
        name = "${driveId}_cutout.webp",
        tags = null,
    )

    private fun image(driveId: String, originalDriveId: String? = null) = DriveImage(
        driveId = driveId,
        localPath = "",
        name = "${driveId}_cutout.webp",
        originalDriveId = originalDriveId,
        folderId = "f1",
    )

    private fun cacheFile(name: String): File =
        File(drive.cacheDir, name).apply { writeText("bytes") }

    /**
     * The use-cases hop to the real [Dispatchers.IO] mid-run, which the test scheduler can't
     * advance through — wait (bounded, real time) for the final observable side effect, then
     * [advanceUntilIdle] to settle the trailing Main-dispatcher writes.
     */
    private suspend fun awaitUntil(condition: () -> Boolean) {
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            val deadline = System.currentTimeMillis() + 5_000
            while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        }
    }

    // ---------- RetagAllUseCase ----------

    private fun retagAll() = RetagAllUseCase(drive, ai, items, sidecarSync, jobLock, prefsRepo)

    @Test
    fun `retag writes tags store-first and enqueues a sidecar sync per item`() = runTest(dispatcher) {
        items.addAll("f1", listOf(row("i1"), row("i2")))
        drive.cached["i1"] = cacheFile("i1.png") // i2 stays uncached → skipped by classify
        ai.classifyResult = ClothingTags(label = "Red dress", type = "dress")

        retagAll().start(listOf(image("i1"), image("i2")))
        advanceUntilIdle()
        awaitUntil { mutations.rows.size == 2 }
        advanceUntilIdle()

        assertEquals(listOf(drive.cached["i1"]), ai.classifiedFiles)
        assertEquals("dress", items.find("i1")!!.second.tags?.type)
        assertNull(items.find("i2")!!.second.tags)
        // The sidecar enqueue covers every item in the snapshot, cached or not.
        assertEquals(
            listOf(SIDECAR_SYNC_KIND to "i1", SIDECAR_SYNC_KIND to "i2"),
            mutations.rows.map { it.kind to it.targetId },
        )
    }

    @Test
    fun `credits exhaustion aborts the retag bulk and resets progress`() = runTest(dispatcher) {
        items.addAll("f1", listOf(row("i1"), row("i2")))
        drive.cached["i1"] = cacheFile("i1.png")
        drive.cached["i2"] = cacheFile("i2.png")
        ai.error = com.librelookai.billing.InsufficientCreditsException(20, 0)

        val useCase = retagAll()
        useCase.start(listOf(image("i1"), image("i2")))
        advanceUntilIdle()

        assertNull(items.find("i1")!!.second.tags)
        assertEquals(0, mutations.rows.size) // abort skips the sidecar enqueue pass
        assertFalse(useCase.progress.value.isRunning)
    }

    // ---------- RemoveAllBackgroundsUseCase ----------

    @Test
    fun `re-remove bg updates bytes in place and archives the original once`() = runTest(dispatcher) {
        items.addAll("f1", listOf(row("i1")))
        cacheFile("i1_original.jpg") // the canonical archived-original cache location
        ai.removeBackgroundResult = cacheFile("i1_processed.png")
        val versions = ItemVersions()

        RemoveAllBackgroundsUseCase(drive, ai, items, sidecarSync, versions, jobLock)
            .start(listOf(image("i1")), uploadFolderId = "f1")
        advanceUntilIdle()
        awaitUntil { mutations.rows.isNotEmpty() }
        advanceUntilIdle()

        // No originalDriveId on the item → the original uploads once, and its new Drive id
        // is stamped onto the store row; the cutout bytes PATCH in place (stable Drive ID).
        assertEquals(listOf("f1" to "i1_original.jpg"), drive.uploadedImages)
        assertEquals(listOf("i1"), drive.updatedImageIds)
        assertEquals("upload-1", items.find("i1")!!.second.originalDriveId)
        assertEquals(setOf("i1"), versions.versions.value.keys)
        assertEquals(listOf(SIDECAR_SYNC_KIND to "i1"), mutations.rows.map { it.kind to it.targetId })
    }
}
