package com.librelookai.shopping

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.librelookai.data.local.CachedWardrobeItem
import com.librelookai.data.session.ClosetSessionHolder
import com.librelookai.testing.FakeDriveService
import com.librelookai.testing.FakeWardrobeItemStore
import com.librelookai.wardrobe.ItemSidecar
import com.librelookai.wardrobe.ItemVersions
import com.librelookai.data.drive.DriveFileDto
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fake-based [ShoppingRepository] tests (refactor § 8, the `TripsRepositoryTest` pattern): the
 * `_shopping/` folder resolution's offline/failure fallbacks + session publish, the derived
 * [ShoppingRepository.items] rules (cached-file filter, createdTime-desc order, shared version
 * overlay) and the Phase-2 reconcile's single-flight / pre-warm memoization become tested
 * invariants. Robolectric only for the SharedPrefs-persisted folder id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ShoppingRepositoryTest {

    private val gson = Gson()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val drive = FakeDriveService()
    private val itemStore = FakeWardrobeItemStore()
    private val itemVersions = ItemVersions()
    private val session = ClosetSessionHolder()

    private fun repo(drive: FakeDriveService = this.drive) =
        ShoppingRepository(context, drive, itemStore, itemVersions, session)

    private fun row(driveId: String, createdTimeMs: Long = 0L) =
        CachedWardrobeItem(driveId = driveId, name = "${driveId}_cutout.webp", tags = null, createdTimeMs = createdTimeMs)

    /** Puts [driveId]'s image bytes into the scriptable local Drive cache. */
    private fun cacheImage(driveId: String): File =
        File(drive.cacheDir, driveId).apply { writeText("bytes") }.also { drive.cached[driveId] = it }

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- Folder resolution ----

    @Test
    fun `an online resolve publishes into the closet session and persists across instances`() = runTest {
        val repo = repo()

        assertEquals("shopping-folder", repo.resolveFolder(online = true))
        advanceUntilIdle() // let the session-publish collector run

        assertEquals("shopping-folder", repo.folderId.value)
        assertEquals("shopping-folder", session.session.value.shoppingFolderId)

        // A fresh (cold-start) instance finds the persisted id without any Drive call.
        assertEquals("shopping-folder", repo().resolveFolder(online = false))
    }

    @Test
    fun `an offline resolve with nothing persisted is null`() = runTest {
        assertNull(repo().resolveFolder(online = false))
    }

    @Test
    fun `a failed online resolve falls back to the persisted id`() = runTest {
        repo().resolveFolder(online = true) // persist "shopping-folder"

        val downDrive = object : FakeDriveService() {
            override suspend fun getOrCreateShoppingFolder(rootFolderId: String): String =
                error("drive down")
        }

        assertEquals("shopping-folder", repo(downDrive).resolveFolder(online = true))
    }

    // ---- Derived items ----

    @Test
    fun `items derives the resolved folder's cached rows, newest first, with the version overlay`() = runTest {
        cacheImage("old")
        cacheImage("new")
        itemStore.replaceFolder(
            "shopping-folder",
            listOf(row("old", createdTimeMs = 1L), row("new", createdTimeMs = 2L), row("uncached", createdTimeMs = 3L)),
        )
        itemStore.replaceFolder("other-closet", listOf(row("elsewhere", createdTimeMs = 9L)))
        itemVersions.bump("new")
        val repo = repo()
        repo.resolveFolder(online = true)

        val items = repo.items.first { it.isNotEmpty() }

        assertEquals(listOf("new", "old"), items.map { it.driveId })
        assertTrue(items.first().version > 0L)
        assertEquals(0L, items.last().version)
    }

    // ---- Phase-2 reconcile ----

    @Test
    fun `refreshFromDrive downloads uncached cutouts plus sidecars and replaces the store`() = runTest {
        drive.filesByFolder["shopping-folder"] = listOf(DriveFileDto(id = "i1", name = "i1_cutout.webp"))
        drive.sidecarFilesByFolder["shopping-folder"] = listOf(DriveFileDto(id = "s1", name = "i1.json"))
        drive.fileContents["s1"] = gson.toJson(ItemSidecar(tags = null, originalDriveId = "orig-1"))
        val repo = repo()
        repo.resolveFolder(online = true)

        repo.refreshFromDrive()

        val stored = itemStore.itemsFor("shopping-folder").single()
        assertEquals("i1", stored.driveId)
        assertEquals("orig-1", stored.originalDriveId)
        assertEquals("s1", stored.sidecarDriveId)
        assertTrue(drive.cached.containsKey("i1")) // downloadToCache ran for the uncached cutout
    }

    @Test
    fun `refreshFromDrive once is memoized on success, a plain refresh re-syncs`() = runTest {
        val repo = repo()
        repo.resolveFolder(online = true)

        repo.refreshFromDrive()
        repo.refreshFromDrive(once = true) // the VM-init pre-warm — must not re-list Drive
        assertEquals(1, drive.listFilesCalls)

        repo.refreshFromDrive() // the Shopping tab's per-visit loadItems still re-syncs
        assertEquals(2, drive.listFilesCalls)
    }

    @Test
    fun `refreshFromDrive is a no-op while the folder is unresolved`() = runTest {
        repo().refreshFromDrive()

        assertEquals(0, drive.listFilesCalls)
    }

    @Test
    fun `a failed refresh rethrows to the caller and the next call retries`() = runTest {
        var failures = 0
        drive.onListFiles = {
            if (failures == 0) {
                failures++
                error("drive down")
            }
        }
        drive.filesByFolder["shopping-folder"] = listOf(DriveFileDto(id = "i1", name = "i1_cutout.webp"))
        val repo = repo()
        repo.resolveFolder(online = true)

        assertTrue(runCatching { repo.refreshFromDrive() }.isFailure)

        repo.refreshFromDrive()
        assertEquals(2, drive.listFilesCalls)
        assertEquals(listOf("i1"), itemStore.itemsFor("shopping-folder").map { it.driveId })
    }

    @Test
    fun `concurrent refreshes join the in-flight listing (single-flight)`() = runTest {
        val gate = CompletableDeferred<Unit>()
        drive.onListFiles = { gate.await() }
        val repo = repo()
        repo.resolveFolder(online = true)

        val first = launch { repo.refreshFromDrive() }
        val second = launch { repo.refreshFromDrive() }
        advanceUntilIdle() // both callers reach the gate / join the shared Deferred
        gate.complete(Unit)
        first.join()
        second.join()

        assertEquals(1, drive.listFilesCalls)
    }
}
