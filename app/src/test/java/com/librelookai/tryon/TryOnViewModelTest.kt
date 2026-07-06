package com.librelookai.tryon

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.librelookai.billing.InsufficientCreditsException
import com.librelookai.data.drive.DrainScheduler
import com.librelookai.data.drive.SyncEngine
import com.librelookai.data.model.TryOn
import com.librelookai.gemini.AiRetry
import com.librelookai.gemini.ClothingTags
import com.librelookai.testing.FakeAiClient
import com.librelookai.testing.FakeDriveService
import com.librelookai.testing.FakeMutationStore
import com.librelookai.testing.FakeTryOnStore
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.WardrobeDeleteSyncHandler
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
 * Fake-based suite for the composer half of the try-on VM split (refactor § 8 — the
 * `ShoppingClosetViewModelTest` pattern): draft seeding, the outfit-link demotion on manual
 * edits, the generation guards (missing photos, body coverage) and result/error paths, and
 * `saveCurrent`'s upload → cache → queued-index-write funnel with the real
 * [TryOnIndexSyncHandler] registered, so the tests prove store row → drained `_tryons.json`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TryOnViewModelTest {

    private val gson = Gson()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val drive = FakeDriveService()
    private val gemini = FakeAiClient()
    private val store = FakeTryOnStore()
    private val mutationStore = FakeMutationStore()
    private val syncEngine = SyncEngine(
        mutationStore,
        setOf(TryOnIndexSyncHandler(drive, store), WardrobeDeleteSyncHandler(drive)),
        object : DrainScheduler {
            override fun ensureScheduled() {}
        },
    )
    private val aiRetry = AiRetry()
    private val repo = TryOnRepository(drive, store, mutationStore, syncEngine)

    private fun vm(d: FakeDriveService = drive) = TryOnViewModel(app, gemini, aiRetry, d, repo)

    /** A wardrobe item whose cutout bytes exist locally (the generate path filters on that). */
    private fun item(driveId: String, type: String): DriveImage {
        val f = File(drive.cacheDir, "$driveId.webp").apply { writeText("bytes") }
        return DriveImage(
            driveId = driveId, localPath = f.absolutePath,
            name = "${driveId}_cutout.webp", tags = ClothingTags(type = type),
        )
    }

    private fun personFile(): File = File(drive.cacheDir, "person.jpg").apply { writeText("me") }

    private fun resultFile(): File =
        File(drive.cacheDir, "generated.png").apply { writeText("png") }

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

    // ---- draft seeding / source provenance ----

    @Test
    fun `openComposer seeds the draft and infers the outfit source from a linked outfit id`() {
        val vm = vm()

        vm.openComposer(setOf("a", "b"), sourceOutfitId = "o-1", sourceContext = "Summer look")

        val s = vm.state.value
        assertEquals(setOf("a", "b"), s.itemIds)
        assertEquals("o-1", s.sourceOutfitId)
        assertEquals(TryOnSourceKind.OUTFIT, s.sourceKind)
        assertEquals("Summer look", s.sourceContext)
        assertNull(s.resultPath)
        assertFalse(s.isResultSaved)
    }

    @Test
    fun `manual edits drop the outfit link and demote the source to wardrobe`() {
        val vm = vm()
        vm.openComposer(setOf("a"), sourceOutfitId = "o-1")

        vm.addItem("b")

        val s = vm.state.value
        assertEquals(setOf("a", "b"), s.itemIds)
        assertNull(s.sourceOutfitId)
        assertEquals(TryOnSourceKind.WARDROBE, s.sourceKind)
    }

    @Test
    fun `manual edits keep a shopping source so provenance survives`() {
        val vm = vm()
        vm.openComposer(setOf("a"), sourceKind = TryOnSourceKind.SHOPPING, sourceContext = "Store find")

        vm.removeItem("a")

        assertEquals(TryOnSourceKind.SHOPPING, vm.state.value.sourceKind)
    }

    // ---- generate guards ----

    @Test
    fun `generate refuses without a reference photo and never calls the AI`() = runTest {
        val vm = vm()
        val dress = item("d1", "dress")
        vm.openComposer(setOf("d1"))

        vm.generate(emptyList(), listOf(dress), "")
        advanceUntilIdle()

        assertEquals(com.librelookai.R.string.tryon_missing_photos_items, vm.state.value.errorRes)
        assertTrue(gemini.tryOnCalls.isEmpty())
        assertFalse(vm.state.value.isGenerating)
    }

    @Test
    fun `generate refuses a selection that leaves the lower body uncovered`() = runTest {
        val vm = vm()
        val top = item("t1", "t-shirt")
        vm.openComposer(setOf("t1"))

        vm.generate(listOf(personFile()), listOf(top), "")
        advanceUntilIdle()

        assertEquals(com.librelookai.R.string.tryon_coverage_blocked, vm.state.value.errorRes)
        assertTrue(gemini.tryOnCalls.isEmpty())
    }

    // ---- generate result paths ----

    @Test
    fun `a successful generation stores the result path and registers the one-tap retry`() = runTest {
        gemini.tryOnResult = resultFile()
        val vm = vm()
        val top = item("t1", "t-shirt")
        val jeans = item("j1", "jeans")
        vm.openComposer(setOf("t1", "j1"))

        vm.generate(listOf(personFile()), listOf(top, jeans), "prefs")
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals(gemini.tryOnResult!!.absolutePath, s.resultPath)
        assertFalse(s.isGenerating)
        assertFalse(s.isResultSaved)
        assertEquals(1, gemini.tryOnCalls.size)
        assertNotNull(aiRetry.action)
    }

    @Test
    fun `a null generation degrades to the failed errorRes`() = runTest {
        val vm = vm()
        vm.openComposer(setOf("d1"))

        vm.generate(listOf(personFile()), listOf(item("d1", "dress")), "")
        advanceUntilIdle()

        assertEquals(com.librelookai.R.string.tryon_generate_failed, vm.state.value.errorRes)
        assertNull(vm.state.value.resultPath)
        assertFalse(vm.state.value.isGenerating)
    }

    @Test
    fun `insufficient credits reset the loading state without a local error (global dialog owns it)`() = runTest {
        gemini.error = InsufficientCreditsException(needed = 10, have = 2)
        val vm = vm()
        vm.openComposer(setOf("d1"))

        vm.generate(listOf(personFile()), listOf(item("d1", "dress")), "")
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.isGenerating)
        assertNull(s.error)
        assertNull(s.errorRes)
    }

    // ---- saveCurrent ----

    @Test
    fun `saveCurrent uploads, caches under the minted id, and the queued index write reaches Drive`() = runTest {
        // A pre-existing Drive entry the pre-warm-empty store doesn't know about: the save's
        // Drive-read merge base must keep it.
        store.replaceAll(emptyList())
        drive.tryOnsJsonByRoot[drive.rootFolderId] = gson.toJson(
            listOf(TryOn(imageDriveId = "old", imageName = "old.png", sourceKind = "wardrobe")),
        )
        gemini.tryOnResult = resultFile()
        val vm = vm()
        val dress = item("d1", "dress")
        vm.openComposer(setOf("d1"), sourceOutfitId = "o-1", sourceContext = "Summer look")
        vm.generate(listOf(personFile()), listOf(dress), "")
        advanceUntilIdle()

        vm.saveCurrent(listOf(dress))

        // isResultSaved lands after writeAll returns, and drain() is inline — queue drained.
        val s = vm.state.first { it.isResultSaved }
        assertFalse(s.isSaving)
        val saved = store.flow.value.single { it.imageDriveId != "old" }
        assertEquals(listOf("${dress.driveId}_cutout.webp"), saved.itemNames)
        assertEquals("o-1", saved.sourceOutfitId)
        assertEquals("outfit", saved.sourceKind)
        assertTrue(repo.cacheFile(saved.imageDriveId).exists())
        // The § 2 funnel drained: the index on Drive holds both entries, nothing left queued.
        assertEquals(
            setOf(saved.imageDriveId, "old"),
            indexJson().map { it.imageDriveId }.toSet(),
        )
        assertEquals(0, mutationStore.rows.size)
    }

    @Test
    fun `a failed upload surfaces the error and clears isSaving`() = runTest {
        val failingDrive = object : FakeDriveService() {
            override suspend fun uploadTryOnImage(rootFolderId: String, name: String, imageFile: File): String =
                error("drive down")
        }
        gemini.tryOnResult = resultFile()
        val vm = vm(failingDrive)
        val dress = item("d1", "dress")
        vm.openComposer(setOf("d1"))
        vm.generate(listOf(personFile()), listOf(dress), "")
        advanceUntilIdle()

        vm.saveCurrent(listOf(dress))

        val s = vm.state.first { it.error != null }
        assertFalse(s.isSaving)
        assertFalse(s.isResultSaved)
        assertEquals("drive down", s.error)
        assertTrue(store.flow.value.isEmpty())
    }
}
