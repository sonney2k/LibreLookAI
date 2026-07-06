package com.librelookai.tryon

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.librelookai.data.drive.DrainScheduler
import com.librelookai.data.drive.SyncEngine
import com.librelookai.data.model.TryOn
import com.librelookai.testing.FakeDriveService
import com.librelookai.testing.FakeMutationStore
import com.librelookai.testing.FakeTryOnStore
import com.librelookai.wardrobe.WardrobeDeleteSyncHandler
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fake-based suite for the history half of the try-on VM split (refactor § 8): the init
 * pre-warm reconciling Drive into the derived feed, and the local-first delete over the § 2
 * queue with the real [TryOnIndexSyncHandler] + [WardrobeDeleteSyncHandler] registered — the
 * tests prove feed drop → drained Drive file delete → drained index rewrite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TryOnHistoryViewModelTest {

    private val gson = Gson()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val drive = FakeDriveService()
    private val store = FakeTryOnStore()
    private val mutationStore = FakeMutationStore()
    private val syncEngine = SyncEngine(
        mutationStore,
        setOf(TryOnIndexSyncHandler(drive, store), WardrobeDeleteSyncHandler(drive)),
        object : DrainScheduler {
            override fun ensureScheduled() {}
        },
    )

    private fun repo(s: FakeTryOnStore = store) = TryOnRepository(drive, s, mutationStore, syncEngine)

    private fun vm(r: TryOnRepository = repo()) = TryOnHistoryViewModel(app, r)

    private fun tryOn(imageDriveId: String, createdAt: Long = 0L) = TryOn(
        id = "tryon-$imageDriveId",
        imageDriveId = imageDriveId,
        imageName = "$imageDriveId.png",
        createdAt = createdAt,
        sourceOutfitId = "o-1",
        sourceKind = "outfit",
    )

    /** Puts [imageDriveId]'s image bytes into the local Drive cache (the derivation's filter). */
    private fun cacheImage(imageDriveId: String): File =
        File(drive.cacheDir, "tryon_$imageDriveId.png").apply { writeText("bytes") }

    private fun indexJson(): List<TryOn> = gson.fromJson(
        drive.tryOnsJsonByRoot[drive.rootFolderId],
        object : TypeToken<List<TryOn>>() {}.type,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the init pre-warm reconciles Drive into the derived history, newest first`() = runTest {
        cacheImage("a")
        cacheImage("b")
        drive.tryOnsJsonByRoot[drive.rootFolderId] =
            gson.toJson(listOf(tryOn("a", createdAt = 1L), tryOn("b", createdAt = 2L)))
        val vm = vm()

        val history = vm.state.first { it.history.size == 2 }.history

        assertEquals(listOf("b", "a"), history.map { it.imageDriveId })
    }

    @Test
    fun `deleteTryOns drops the entry locally and the queued file delete + index rewrite reach Drive`() = runTest {
        cacheImage("dead")
        cacheImage("keep")
        drive.tryOnsJsonByRoot[drive.rootFolderId] =
            gson.toJson(listOf(tryOn("dead"), tryOn("keep")))
        val repo = repo()
        val vm = vm(repo)
        val dead = vm.state.first { it.history.size == 2 }.history.single { it.imageDriveId == "dead" }

        vm.deleteTryOns(listOf(dead))

        val history = vm.state.first { it.history.size == 1 }.history
        assertEquals(listOf("keep"), history.map { it.imageDriveId })
        assertFalse(repo.cacheFile("dead").exists())
        // Both mutations were enqueued before the store write the feed followed; a test-side
        // drain queues behind the in-flight one (single-flight), so afterwards the queue is
        // settled and the Drive effects are observable.
        syncEngine.drain()
        assertEquals(listOf("dead"), drive.deletedFileIds)
        assertEquals(listOf("keep"), indexJson().map { it.imageDriveId })
        assertEquals(0, mutationStore.rows.size)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `a failed local delete surfaces the error, and clearError resets it`() = runTest {
        val failingStore = object : FakeTryOnStore() {
            override suspend fun tryOns(): List<TryOn> = error("boom")
        }
        val vm = vm(repo(failingStore))

        vm.deleteTryOns(listOf(tryOn("a")))

        val s = vm.state.first { it.error != null }
        assertEquals("boom", s.error)
        assertNull(s.errorRes)

        vm.clearError()
        assertNull(vm.state.value.error)
    }
}
