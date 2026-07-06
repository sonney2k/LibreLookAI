package com.librelookai.wardrobe

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.librelookai.data.drive.DrainScheduler
import com.librelookai.data.drive.DriveFileDto
import com.librelookai.data.drive.SyncEngine
import com.librelookai.data.local.CachedWardrobeItem
import com.librelookai.data.model.Location
import com.librelookai.data.session.ClosetSession
import com.librelookai.data.session.ClosetSessionHolder
import com.librelookai.gemini.ClothingTags
import com.librelookai.testing.FakeDriveService
import com.librelookai.testing.FakeMutationStore
import com.librelookai.testing.FakeWardrobeItemStore
import com.librelookai.util.NetworkMonitor
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Fake-based [WardrobeRepository] tests (refactor § 8, the `TripsRepositoryTest` pattern): the
 * closet-session-driven view scope over the derived [WardrobeRepository.images] /
 * [WardrobeRepository.allLocationImages] flows, the two-phase reconcile (download + sidecar
 * stamping into the store), the recently-moved suppression against Drive's eventually-consistent
 * listings, the § 2 move/delete queue funnels and the uncached-closet prefetch become tested
 * invariants. Robolectric for the `ConnectivityManager`-based Phase-2 online gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WardrobeRepositoryTest {

    private val gson = Gson()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val drive = FakeDriveService()
    private val itemStore = FakeWardrobeItemStore()
    private val mutationStore = FakeMutationStore()
    // No handlers registered: drain() halts on the unknown kind, leaving the queued rows
    // observable (the § 2 engine contract — unknown kinds halt without data loss).
    private val syncEngine = SyncEngine(mutationStore, emptySet(), object : DrainScheduler {
        override fun ensureScheduled() {}
    })
    private val itemVersions = ItemVersions()
    private val session = ClosetSessionHolder()

    private fun repo() = WardrobeRepository(
        context, drive, itemStore, mutationStore, syncEngine, itemVersions,
        NetworkMonitor(context), session,
    )

    private fun row(driveId: String) =
        CachedWardrobeItem(driveId = driveId, name = "${driveId}_cutout.webp", tags = null)

    private fun image(driveId: String, folderId: String, originalDriveId: String? = null, sidecarDriveId: String? = null) =
        DriveImage(
            driveId = driveId, localPath = "", name = "${driveId}_cutout.webp",
            originalDriveId = originalDriveId, sidecarDriveId = sidecarDriveId, folderId = folderId,
        )

    /** Puts [driveId]'s image bytes into the local Drive cache and lists it in [folderId]. */
    private fun itemOnDrive(driveId: String, folderId: String): File {
        drive.filesByFolder[folderId] =
            drive.filesByFolder[folderId].orEmpty() + DriveFileDto(id = driveId, name = "${driveId}_cutout.webp")
        return File(drive.cacheDir, driveId).apply { writeText("bytes") }.also { drive.cached[driveId] = it }
    }

    private fun location(folderId: String) = Location(name = folderId, folderId = folderId)

    private fun activate(activeLocationId: String, vararg folderIds: String) =
        session.setClosets(folderIds.map { location(it) }, activeLocationId, defaultClosetFolderId = null)

    /** The Phase-2 arms gate on a validated active network — Robolectric defaults to none. */
    private fun goOnline() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        goOnline()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- Session-driven scope (§ 5 slice 1 / 4a) ----

    @Test
    fun `the closet session drives the view scope and the import target`() = runTest {
        itemOnDrive("a", "f1")
        itemOnDrive("b", "f2")
        itemStore.replaceFolder("f1", listOf(row("a")))
        itemStore.replaceFolder("f2", listOf(row("b")))
        val repo = repo()

        activate("f1", "f1", "f2")
        val active = repo.images.first { it.isNotEmpty() }
        assertEquals(listOf("a"), active.map { it.driveId })
        assertEquals("f1", active.single().folderId)
        assertEquals("f1", repo.importTargetFolderId.value) // default = first closet

        activate("f2", "f1", "f2")
        assertEquals(listOf("b"), repo.images.first { it.map { i -> i.driveId } == listOf("b") }.map { it.driveId })
        assertEquals(2, repo.scopeChanges.value)
    }

    @Test
    fun `All-locations mode merges every closet into the view scope`() = runTest {
        itemOnDrive("a", "f1")
        itemOnDrive("b", "f2")
        itemStore.replaceFolder("f1", listOf(row("a")))
        itemStore.replaceFolder("f2", listOf(row("b")))
        val repo = repo()

        activate(ClosetSession.ALL_LOCATIONS_ID, "f1", "f2")

        val images = repo.images.first { it.size == 2 }
        assertEquals(setOf("a" to "f1", "b" to "f2"), images.map { it.driveId to it.folderId }.toSet())
    }

    @Test
    fun `bumping an item's version restamps the derived images`() = runTest {
        itemOnDrive("a", "f1")
        itemStore.replaceFolder("f1", listOf(row("a")))
        val repo = repo()
        activate("f1", "f1")
        assertEquals(0L, repo.images.first { it.isNotEmpty() }.single().version)

        itemVersions.bump("a")

        assertTrue(repo.images.first { imgs -> imgs.any { it.version > 0L } }.single().version > 0L)
    }

    // ---- Two-phase reconcile ----

    @Test
    fun `a cold reconcile downloads images, reads sidecars and fills the store`() = runTest {
        drive.filesByFolder["f1"] = listOf(DriveFileDto(id = "a", name = "a_cutout.webp"))
        drive.sidecarFilesByFolder["f1"] = listOf(DriveFileDto(id = "s-a", name = "a.json"))
        drive.fileContents["s-a"] =
            gson.toJson(ItemSidecar(tags = ClothingTags(type = "jacket"), originalDriveId = "orig-a"))
        val repo = repo()

        activate("f1", "f1")

        val image = repo.images.first { it.isNotEmpty() }.single()
        assertEquals("a", image.driveId)
        assertEquals("jacket", image.tags?.type)
        assertEquals("orig-a", image.originalDriveId)
        assertEquals("s-a", image.sidecarDriveId)
        assertTrue(drive.cached.containsKey("a")) // downloadToCache ran for the uncached cutout
        assertEquals(listOf("a"), itemStore.itemsFor("f1").map { it.driveId })
        repo.syncStatus.first { !it.isLoading && !it.isSyncing } // the flags settle
    }

    @Test
    fun `a recently-moved item is suppressed from the stale source listing`() = runTest {
        // m is cached + homed in f1; Drive still lists it under f1 (a move's listing change can
        // lag for minutes) and not yet under f2. Both closets are cached, so no prefetch runs.
        itemOnDrive("m", "f1")
        itemStore.replaceFolder("f1", listOf(row("m")))
        itemStore.replaceFolder("f2", emptyList())
        val gate = CompletableDeferred<Unit>()
        drive.onListFiles = { gate.await() }
        val repo = repo()
        activate("f1", "f1", "f2")
        val shown = repo.images.first { imgs -> imgs.any { it.driveId == "m" } } // Phase-1 paint
        repo.syncStatus.first { it.isSyncing } // Phase 2 is now parked on the gated listing

        repo.notifyItemsMovedTo("f2", shown)
        repo.images.first { imgs -> imgs.none { it.driveId == "m" } } // the store re-home applied

        gate.complete(Unit) // let the reconcile see f1's stale listing
        repo.syncStatus.first { !it.isSyncing }

        assertEquals("f2", itemStore.find("m")?.first) // not resurrected into the source closet
        assertEquals(emptyList<CachedWardrobeItem>(), itemStore.itemsFor("f1"))
    }

    // ---- Queued mutations (§ 2) ----

    @Test
    fun `enqueueMoves queues one payload-carrying move per item and drains`() = runTest {
        val repo = repo()

        repo.enqueueMoves(
            listOf(image("m", folderId = "f1", originalDriveId = "o-m", sidecarDriveId = "s-m")),
            targetFolderId = "f2",
        )

        val mutation = mutationStore.rows.single()
        assertEquals(ITEM_MOVE_KIND, mutation.kind)
        assertEquals("m", mutation.targetId)
        assertEquals("f2", mutation.folderId)
        assertEquals(
            MoveItemPayload("f1", "f2", "o-m", "s-m"),
            gson.fromJson(mutation.payload, MoveItemPayload::class.java),
        )
    }

    @Test
    fun `deleteItems clears the store rows and queues payload-carrying deletes`() = runTest {
        itemStore.replaceFolder("f1", listOf(row("a")))
        itemStore.replaceFolder("f2", listOf(row("b")))
        val repo = repo()

        repo.deleteItems(
            listOf(
                image("a", folderId = "f1", originalDriveId = "o-a", sidecarDriveId = "s-a"),
                image("b", folderId = "f2"),
            ),
        )

        assertEquals(emptyList<CachedWardrobeItem>(), itemStore.itemsFor("f1"))
        assertEquals(emptyList<CachedWardrobeItem>(), itemStore.itemsFor("f2"))
        assertEquals(listOf(ITEM_DELETE_KIND, ITEM_DELETE_KIND), mutationStore.rows.map { it.kind })
        // File ids ride in the payload — the Room row is gone before the drain runs.
        val payloadA = mutationStore.rows.first { it.targetId == "a" }
        assertEquals("f1", payloadA.folderId)
        assertEquals(
            listOf("a", "o-a", "s-a"),
            gson.fromJson(payloadA.payload, DeleteItemPayload::class.java).fileIds,
        )
    }

    // ---- Prefetch ----

    @Test
    fun `configured closets without a local cache are prefetched into the snapshot`() = runTest {
        itemStore.replaceFolder("f1", emptyList()) // visited before → cached, skipped
        drive.filesByFolder["f2"] = listOf(DriveFileDto(id = "b", name = "b_cutout.webp"))
        drive.sidecarFilesByFolder["f2"] = listOf(DriveFileDto(id = "s-b", name = "b.json"))
        drive.fileContents["s-b"] = gson.toJson(ItemSidecar(tags = null, originalDriveId = "orig-b"))
        val repo = repo()

        activate("f1", "f1", "f2")

        val snapshot = repo.allLocationImages.first { imgs -> imgs.any { it.driveId == "b" } }
        assertEquals("orig-b", snapshot.single { it.driveId == "b" }.originalDriveId)
        assertTrue(drive.cached.containsKey("b")) // downloaded, not just indexed
        assertEquals(listOf("b"), itemStore.itemsFor("f2").map { it.driveId })
    }
}
