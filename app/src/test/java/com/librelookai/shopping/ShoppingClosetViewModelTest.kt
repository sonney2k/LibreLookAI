package com.librelookai.shopping

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.librelookai.data.drive.DrainScheduler
import com.librelookai.data.drive.SyncEngine
import com.librelookai.data.local.CachedWardrobeItem
import com.librelookai.data.session.ClosetSessionHolder
import com.librelookai.data.session.UserPreferencesRepository
import com.librelookai.gemini.ClothingTags
import com.librelookai.testing.FakeAiClient
import com.librelookai.testing.FakeDriveService
import com.librelookai.testing.FakeMutationStore
import com.librelookai.testing.FakeWardrobeItemStore
import com.librelookai.wardrobe.ItemSidecar
import com.librelookai.wardrobe.ItemVersions
import com.librelookai.wardrobe.SidecarSyncQueue
import com.librelookai.wardrobe.WardrobeMoveSyncHandler
import com.librelookai.wardrobe.WardrobeSidecarSyncHandler
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The first fake-based ViewModel suite (refactor § 8 — VMs are constructible with fakes now
 * that both seams exist): [ShoppingClosetViewModel]'s tag-edit paths ride the § 2
 * `wardrobe.sidecarSync` queue (the former direct-Drive `saveSidecar` is gone) — the real
 * [WardrobeSidecarSyncHandler] is registered, so the tests prove the whole funnel: store-first
 * row write → queued mutation → drained sidecar upsert → sidecar id stamped back on the row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ShoppingClosetViewModelTest {

    private val gson = Gson()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val drive = FakeDriveService()
    private val gemini = FakeAiClient()
    private val itemStore = FakeWardrobeItemStore()
    private val mutationStore = FakeMutationStore()
    private val sidecarHandler = WardrobeSidecarSyncHandler(drive, itemStore)
    private val syncEngine = SyncEngine(mutationStore, setOf(sidecarHandler), object : DrainScheduler {
        override fun ensureScheduled() {}
    })
    private val itemVersions = ItemVersions()
    private val prefsRepo = UserPreferencesRepository()

    private fun vm() = ShoppingClosetViewModel(
        app, drive, gemini, itemStore, mutationStore, syncEngine,
        ShoppingIngestionQueue(drive, gemini, itemStore, prefsRepo),
        ShoppingRepository(app, drive, itemStore, itemVersions, ClosetSessionHolder()),
        itemVersions,
        SidecarSyncQueue(itemStore, mutationStore, syncEngine),
        WardrobeMoveSyncHandler(drive, itemStore),
        prefsRepo,
    )

    private fun row(driveId: String) =
        CachedWardrobeItem(driveId = driveId, name = "${driveId}_cutout.webp", tags = null, originalDriveId = "orig-$driveId")

    /** Awaits the wishlist row once the queued sidecar save has stamped its Drive id back. */
    private suspend fun awaitSidecarStamped(driveId: String): CachedWardrobeItem =
        itemStore.observeItems(listOf(drive.shoppingFolderId))
            .first { it[drive.shoppingFolderId]?.any { r -> r.driveId == driveId && r.sidecarDriveId != null } == true }
            .getValue(drive.shoppingFolderId).single { it.driveId == driveId }

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `updateTags writes the store row first and rides the sidecarSync queue to Drive`() = runTest {
        itemStore.replaceFolder("shopping-folder", listOf(row("i1")))
        val vm = vm()

        vm.updateTags("i1", ClothingTags(type = "sneakers"))

        val stored = awaitSidecarStamped("i1")
        assertEquals("sneakers", stored.tags?.type)
        assertEquals("sidecar-i1.json", stored.sidecarDriveId)
        // The handler wrote the sidecar from the *row* (tags + original id), into the row's folder.
        val sidecar = gson.fromJson(drive.upsertedSidecars["shopping-folder/i1.json"], ItemSidecar::class.java)
        assertEquals("sneakers", sidecar.tags?.type)
        assertEquals("orig-i1", sidecar.originalDriveId)
        assertEquals(0, mutationStore.rows.size) // drained, not left behind
    }

    @Test
    fun `tagImage classifies the cached image and persists through the same queue`() = runTest {
        val cached = File(drive.cacheDir, "i1").apply { writeText("bytes") }
        drive.cached["i1"] = cached
        itemStore.replaceFolder("shopping-folder", listOf(row("i1")))
        gemini.classifyResult = ClothingTags(type = "jacket")
        val vm = vm()

        vm.tagImage("i1")

        val stored = awaitSidecarStamped("i1")
        assertEquals("jacket", stored.tags?.type)
        assertEquals(listOf(cached), gemini.classifiedFiles)
        assertEquals("sidecar-i1.json", stored.sidecarDriveId)
    }
}
