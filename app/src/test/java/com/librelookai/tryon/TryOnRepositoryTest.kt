package com.librelookai.tryon

import com.google.gson.Gson
import com.librelookai.data.drive.DrainScheduler
import com.librelookai.data.drive.SyncEngine
import com.librelookai.data.model.TryOn
import com.librelookai.testing.FakeDriveService
import com.librelookai.testing.FakeMutationStore
import com.librelookai.testing.FakeTryOnStore
import com.librelookai.wardrobe.DeleteItemPayload
import com.librelookai.wardrobe.ITEM_DELETE_KIND
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fake-based [TryOnRepository] tests (refactor § 8, the `TripsRepositoryTest` pattern): the
 * cached-image-file filter on the derived [TryOnRepository.history], the legacy source
 * migration, the Phase-2 refresh (missing-image download + store mirror, `once` pre-warm
 * memoization, single-flight) and the § 2 local-first write/delete queue funnels become
 * tested invariants (handler behavior is [TryOnSyncHandlersTest]'s concern).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TryOnRepositoryTest {

    private val gson = Gson()
    private val drive = FakeDriveService()
    private val store = FakeTryOnStore()
    private val mutationStore = FakeMutationStore()
    // No handlers registered: drain() halts on the unknown kind, leaving the queued rows
    // observable (the § 2 engine contract — unknown kinds halt without data loss).
    private val syncEngine = SyncEngine(mutationStore, emptySet(), object : DrainScheduler {
        override fun ensureScheduled() {}
    })

    private fun repo() = TryOnRepository(drive, store, mutationStore, syncEngine)

    private fun tryOn(
        imageDriveId: String,
        createdAt: Long = 0L,
        sourceOutfitId: String? = "outfit-1",
        sourceKind: String = "outfit",
    ) = TryOn(
        id = "tryon-$imageDriveId",
        imageDriveId = imageDriveId,
        imageName = "$imageDriveId.png",
        createdAt = createdAt,
        sourceOutfitId = sourceOutfitId,
        sourceKind = sourceKind,
    )

    /** Puts [imageDriveId]'s image bytes into the local Drive cache (the derivation's filter). */
    private fun cacheImage(imageDriveId: String): File =
        File(drive.cacheDir, "tryon_$imageDriveId.png").apply { writeText("bytes") }

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `history derives rows with a cached image file, newest first`() = runTest {
        cacheImage("a")
        cacheImage("c")
        store.replaceAll(
            listOf(
                tryOn("a", createdAt = 1L),
                tryOn("b", createdAt = 2L), // no cached image → skipped until downloaded
                tryOn("c", createdAt = 3L),
            ),
        )
        val repo = repo()

        val history = repo.history.first { it.isNotEmpty() }

        assertEquals(listOf("c", "a"), history.map { it.imageDriveId })
        assertEquals(repo.cacheFile("c").absolutePath, history.first().localPath)
    }

    @Test
    fun `history migrates a legacy item-by-item try-on to a wardrobe source`() = runTest {
        cacheImage("legacy")
        cacheImage("outfit")
        store.replaceAll(
            listOf(
                tryOn("legacy", createdAt = 2L, sourceOutfitId = null), // pre-sourceKind default
                tryOn("outfit", createdAt = 1L, sourceOutfitId = "o-1"),
            ),
        )

        val history = repo().history.first { it.isNotEmpty() }

        assertEquals(listOf("wardrobe", "outfit"), history.map { it.sourceKind })
    }

    @Test
    fun `refreshFromDrive downloads missing images and mirrors the index into the store`() = runTest {
        cacheImage("cached")
        drive.tryOnsJsonByRoot[drive.rootFolderId] =
            gson.toJson(listOf(tryOn("cached"), tryOn("missing")))
        val repo = repo()

        repo.refreshFromDrive()

        assertEquals(listOf("missing"), drive.downloadedFileIds)
        assertTrue(repo.cacheFile("missing").exists())
        assertEquals(setOf("cached", "missing"), store.flow.value.map { it.imageDriveId }.toSet())
    }

    @Test
    fun `refreshFromDrive once is memoized on success, a plain refresh re-syncs`() = runTest {
        drive.tryOnsJsonByRoot[drive.rootFolderId] = gson.toJson(listOf(tryOn("a")))
        val repo = repo()

        repo.refreshFromDrive()
        repo.refreshFromDrive(once = true) // the VM-init pre-warm — must not re-read Drive
        assertEquals(1, drive.loadTryOnsJsonCalls)

        repo.refreshFromDrive() // the per-visit refresh still re-syncs
        assertEquals(2, drive.loadTryOnsJsonCalls)
    }

    @Test
    fun `concurrent refreshes join the in-flight load (single-flight)`() = runTest {
        val gate = CompletableDeferred<Unit>()
        drive.onLoadTryOnsJson = { gate.await() }
        drive.tryOnsJsonByRoot[drive.rootFolderId] = gson.toJson(listOf(tryOn("a")))
        val repo = repo()

        val first = launch { repo.refreshFromDrive() }
        val second = launch { repo.refreshFromDrive() }
        advanceUntilIdle() // both callers reach the gate / join the shared Deferred
        gate.complete(Unit)
        first.join()
        second.join()

        assertEquals(1, drive.loadTryOnsJsonCalls)
        assertEquals(listOf("a"), store.flow.value.map { it.imageDriveId })
    }

    @Test
    fun `writeAll is local-first — store mirror plus one queued index sync, no direct Drive write`() = runTest {
        repo().writeAll(listOf(tryOn("a")))

        assertEquals(listOf("tryon-a"), store.flow.value.map { it.id })
        assertEquals(listOf(TRYON_INDEX_SYNC_KIND), mutationStore.rows.map { it.kind })
        assertTrue(drive.tryOnsJsonByRoot.isEmpty())
    }

    @Test
    fun `loadEntries is empty on corrupt index JSON`() = runTest {
        drive.tryOnsJsonByRoot[drive.rootFolderId] = "not json {"

        assertEquals(emptyList<TryOn>(), repo().loadEntries())
    }

    @Test
    fun `deleteTryOns is local-first — cache file and store row go now, file delete then index sync ride the queue`() = runTest {
        store.replaceAll(listOf(tryOn("dead"), tryOn("keep")))
        cacheImage("dead")
        val repo = repo()

        repo.deleteTryOns(listOf(tryOn("dead")))

        assertFalse(repo.cacheFile("dead").exists())
        assertEquals(listOf("keep"), store.flow.value.map { it.imageDriveId })
        // FIFO: the file delete drains before the index rewrite, matching the old order.
        assertEquals(listOf(ITEM_DELETE_KIND, TRYON_INDEX_SYNC_KIND), mutationStore.rows.map { it.kind })
        val payload = gson.fromJson(mutationStore.rows.first().payload, DeleteItemPayload::class.java)
        assertEquals(listOf("dead"), payload.fileIds)
        // Nothing touches Drive until the drain applies the queued mutations.
        assertTrue(drive.deletedFileIds.isEmpty())
        assertTrue(drive.tryOnsJsonByRoot.isEmpty())
    }
}
