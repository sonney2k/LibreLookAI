package com.librelookai.wardrobe

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.librelookai.data.drive.DrainScheduler
import com.librelookai.data.drive.SyncEngine
import com.librelookai.data.local.CachedWardrobeItem
import com.librelookai.data.model.Location
import com.librelookai.data.session.ClosetSessionHolder
import com.librelookai.data.session.UserPreferencesRepository
import com.librelookai.gemini.ClothingTags
import com.librelookai.service.JobLock
import com.librelookai.testing.FakeAiClient
import com.librelookai.testing.FakeDriveService
import com.librelookai.testing.FakeMutationStore
import com.librelookai.testing.FakeSimilarityService
import com.librelookai.testing.FakeWardrobeItemStore
import com.librelookai.util.NetworkMonitor
import java.io.File
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

/**
 * Fake-based suite for the wardrobe VM's *own* logic (refactor § 8 — the load core, per-item
 * ops, ingestion and bulk use-cases have their own suites): the derived-images mirror, the
 * per-scope UI-state reset on a closet switch, the optimistic move/delete halves over the
 * [WardrobeRepository] queue funnels, the buffered scroll-to-item one-shot, and the fuzzy
 * text search over the cross-closet snapshot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WardrobeViewModelTest {

    private val gson = Gson()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val drive = FakeDriveService()
    private val gemini = FakeAiClient()
    private val itemStore = FakeWardrobeItemStore()
    private val mutationStore = FakeMutationStore()
    private val session = ClosetSessionHolder()
    private val prefsRepo = UserPreferencesRepository()

    // Everything with a Main-dispatcher scope is built lazily in [vm] — after setMain.
    private lateinit var repo: WardrobeRepository

    private fun vm(): WardrobeViewModel {
        val syncEngine = SyncEngine(mutationStore, emptySet(), object : DrainScheduler {
            override fun ensureScheduled() {}
        })
        val itemVersions = ItemVersions()
        val jobLock = JobLock(app)
        val sidecarQueue = SidecarSyncQueue(itemStore, mutationStore, syncEngine)
        repo = WardrobeRepository(
            app, drive, itemStore, mutationStore, syncEngine, itemVersions,
            NetworkMonitor(app), session,
        )
        return WardrobeViewModel(
            app, drive,
            ItemIngestionPipeline(app, drive, gemini, itemStore, session, prefsRepo, jobLock, FakeSimilarityService()),
            WardrobeItemOps(app, drive, gemini, itemStore, sidecarQueue, itemVersions, jobLock, prefsRepo),
            repo, jobLock,
            WardrobeMoveSyncHandler(drive, itemStore),
            RetagAllUseCase(drive, gemini, itemStore, sidecarQueue, jobLock, prefsRepo),
            RemoveAllBackgroundsUseCase(drive, gemini, itemStore, sidecarQueue, itemVersions, jobLock),
            WebpConvertUseCase(drive, itemStore, itemVersions, jobLock),
            CutoutBgFixUseCase(drive, itemVersions, jobLock),
            prefsRepo,
        )
    }

    /** Session with [activeFolderId] active (the filter is keyed by folder id, never Location.id). */
    private fun activate(activeFolderId: String, vararg folderIds: String) = session.setClosets(
        (listOf(activeFolderId) + folderIds).map { Location(name = it, folderId = it) },
        activeFolderId,
        defaultClosetFolderId = null,
    )

    /** A store row whose image bytes are in the local Drive cache (the derivation's filter). */
    private suspend fun cachedRow(driveId: String, folderId: String, tags: ClothingTags? = null) {
        drive.cached[driveId] = File(drive.cacheDir, driveId).apply { writeText("bytes") }
        // addAll upserts under the owning folder without clobbering earlier rows.
        itemStore.addAll(folderId, listOf(CachedWardrobeItem(driveId = driveId, name = "${driveId}_cutout.webp", tags = tags)))
    }

    /** Awaits [n] queued rows in real time — repo funnels enqueue on [Dispatchers.IO], off the
     *  virtual clock, so a state-flow await can land before the enqueue does. */
    private suspend fun awaitQueueSize(n: Int) = kotlinx.coroutines.withContext(Dispatchers.Default) {
        while (mutationStore.rows.size < n) kotlinx.coroutines.delay(5)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `state mirrors the repo's derived images for the active closet`() = runTest {
        cachedRow("a", "f1")
        cachedRow("elsewhere", "f2")
        activate("f1", "f2")
        val vm = vm()

        val images = vm.state.first { it.images.isNotEmpty() }.images

        assertEquals(listOf("a"), images.map { it.driveId })
        assertEquals("f1", images.single().folderId)
    }

    @Test
    fun `a closet switch resets the per-scope UI state but keeps the mirrors`() = runTest {
        cachedRow("a", "f1")
        activate("f1", "f2")
        val vm = vm()
        vm.state.first { it.images.isNotEmpty() }
        vm.toggleSelection("a")
        vm.openCapture()
        assertEquals(WardrobeView.CAPTURE, vm.state.value.view)

        activate("f2", "f1")

        val s = vm.state.first { it.selectedIds.isEmpty() && it.view == WardrobeView.GRID }
        assertTrue(s.selectedIds.isEmpty())
        assertEquals(WardrobeView.GRID, s.view)
    }

    @Test
    fun `moveItemsToLocation re-homes optimistically and queues one move per item`() = runTest {
        cachedRow("a", "f1")
        cachedRow("b", "f1")
        activate("f1", "f2")
        val vm = vm()
        vm.state.first { it.images.size == 2 }
        vm.toggleSelection("a")

        vm.moveItemsToLocation(setOf("a"), targetFolderId = "f2")

        // Optimistic half: selection cleared, the store row re-homed (the derived view follows).
        val s = vm.state.first { st -> st.images.none { it.driveId == "a" } }
        assertTrue(s.selectedIds.isEmpty())
        assertEquals("f2", itemStore.find("a")!!.first)
        vm.state.first { !it.isMoving }
        // Queue funnel: one move mutation carrying the from→to pair.
        val move = mutationStore.rows.single()
        assertEquals(ITEM_MOVE_KIND, move.kind)
        assertEquals("a", move.targetId)
        val payload = gson.fromJson(move.payload, MoveItemPayload::class.java)
        assertEquals("f1", payload.sourceFolderId)
        assertEquals("f2", payload.targetFolderId)
    }

    @Test
    fun `a no-op move to the item's own closet only clears the selection`() = runTest {
        cachedRow("a", "f1")
        activate("f1", "f2")
        val vm = vm()
        vm.state.first { it.images.size == 1 }
        vm.toggleSelection("a")

        vm.moveItemsToLocation(setOf("a"), targetFolderId = "f1")

        assertTrue(vm.state.value.selectedIds.isEmpty())
        assertEquals("f1", itemStore.find("a")!!.first)
        assertTrue(mutationStore.rows.isEmpty())
    }

    @Test
    fun `deleteItems clears the selection, drops the rows and queues payload-carrying deletes`() = runTest {
        cachedRow("dead", "f1")
        cachedRow("keep", "f1")
        activate("f1")
        val vm = vm()
        vm.state.first { it.images.size == 2 }
        vm.toggleSelection("dead")

        vm.deleteItems(setOf("dead"))

        val s = vm.state.first { st -> st.images.map { it.driveId } == listOf("keep") }
        assertTrue(s.selectedIds.isEmpty())
        assertEquals(null, itemStore.find("dead"))
        awaitQueueSize(1) // the enqueue follows the store removal on the IO dispatcher
        assertEquals(listOf(ITEM_DELETE_KIND), mutationStore.rows.map { it.kind })
    }

    @Test
    fun `requestScrollToImage buffers a one-shot event for a late-mounting grid`() = runTest {
        activate("f1")
        val vm = vm()

        vm.requestScrollToImage("target") // fired before any collector subscribes (cross-tab)

        assertEquals(WardrobeEvent.ScrollToItem("target"), vm.events.first())
    }

    @Test
    fun `searchByText fuzzy-matches tags across every closet into the find-by-photo sheet`() = runTest {
        cachedRow("shoe", "f1", tags = ClothingTags(label = "White sneakers", type = "sneakers"))
        cachedRow("tee", "f2", tags = ClothingTags(label = "Blue tee", type = "t-shirt"))
        activate("f1", "f2") // active closet is f1 — the search pool must still include f2
        val vm = vm()
        vm.state.first { it.allLocationImages.size == 2 }

        vm.searchByText("sneakers")

        val result = vm.state.first { it.findByPhoto?.isSearching == false }.findByPhoto!!
        assertEquals("sneakers", result.textQuery)
        assertEquals(listOf("shoe"), result.matches.map { it.image.driveId })
    }

    @Test
    fun `fuzzyFilterByText filters inline without touching the sheet state`() = runTest {
        cachedRow("shoe", "f1", tags = ClothingTags(label = "White sneakers", type = "sneakers"))
        cachedRow("tee", "f1", tags = ClothingTags(label = "Blue tee", type = "t-shirt"))
        activate("f1")
        val vm = vm()
        val images = vm.state.first { it.images.size == 2 }.images

        val filtered = vm.fuzzyFilterByText("sneakers", images)

        assertEquals(listOf("shoe"), filtered.map { it.driveId })
        assertEquals(null, vm.state.value.findByPhoto)
    }
}
