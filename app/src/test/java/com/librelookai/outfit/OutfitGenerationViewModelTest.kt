package com.librelookai.outfit

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.librelookai.data.drive.DrainScheduler
import com.librelookai.data.drive.DriveFileDto
import com.librelookai.data.drive.SyncEngine
import com.librelookai.data.model.Outfit
import com.librelookai.data.session.ClosetSessionHolder
import com.librelookai.gemini.AiResult
import com.librelookai.gemini.AiRetry
import com.librelookai.gemini.ClothingTags
import com.librelookai.gemini.FashionTrendsCache
import com.librelookai.gemini.GeminiProgress
import com.librelookai.gemini.GeminiRepository
import com.librelookai.testing.FakeAiClient
import com.librelookai.testing.FakeDriveService
import com.librelookai.testing.FakeMutationStore
import com.librelookai.testing.FakeOutfitEventStore
import com.librelookai.testing.FakeOutfitStore
import com.librelookai.testing.FakeWardrobeItemStore
import com.librelookai.wardrobe.DriveImage
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fake-based suite for the AI **generation** VM (refactor § 8): composer draft seeding /
 * closing, the enhance flow's suggestion parsing + slot application + error/credits degrade
 * paths (scripted [FakeAiClient]), the create/edit save funnels through [OutfitsRepository],
 * and the prediction flow's known-styles filtering. The real [FashionTrendsCache] is used —
 * unconfigured Gemini serves the trends lookup as a graceful null, so no test touches the
 * network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OutfitGenerationViewModelTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val drive = FakeDriveService()
    private val gemini = FakeAiClient()
    private val aiRetry = AiRetry()
    private val itemStore = FakeWardrobeItemStore()
    private val outfitStore = FakeOutfitStore()
    private val eventStore = FakeOutfitEventStore()
    private val mutationStore = FakeMutationStore()
    // No handlers registered: drain() halts on the unknown kind, leaving the queued rows
    // observable (the § 2 engine contract — unknown kinds halt without data loss).
    private val syncEngine = SyncEngine(mutationStore, emptySet(), object : DrainScheduler {
        override fun ensureScheduled() {}
    })
    private val session = ClosetSessionHolder()
    private val repo = OutfitsRepository(drive, itemStore, outfitStore, mutationStore, syncEngine)
    private val trendsCache = FashionTrendsCache(app, drive, GeminiRepository(app, GeminiProgress()))

    private fun vm() = OutfitGenerationViewModel(app, drive, gemini, aiRetry, repo, session, eventStore, trendsCache)

    private fun item(driveId: String, category: String) = DriveImage(
        driveId = driveId, localPath = "", name = "${driveId}_cutout.webp",
        tags = ClothingTags(category = category),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- composer draft ----

    @Test
    fun `openComposer seeds one locked slot per item, layered by its category`() {
        val vm = vm()

        vm.openComposer(
            seedItemIds = setOf("top-1", "pants-1"),
            images = listOf(item("top-1", "tops"), item("pants-1", "bottoms")),
            prefs = null,
            editingStyleId = "o-1",
        )

        val s = vm.state.value
        assertEquals("o-1", s.composerEditingOutfitId)
        assertEquals(setOf("top-1", "pants-1"), s.composerItemIds.toSet())
        val byItem = s.composerSlots.associateBy { it.selectedItemId }
        assertEquals(Layer.Top, byItem["top-1"]?.category)
        assertEquals(Layer.Bottom, byItem["pants-1"]?.category)
        assertTrue(s.composerSlots.all { it.isLocked })
    }

    @Test
    fun `a scratch composer opens the default slot set without accessory or one-piece`() {
        val vm = vm()

        vm.openComposer(seedItemIds = emptySet(), images = emptyList(), prefs = null)

        val layers = vm.state.value.composerSlots.map { it.category }
        assertFalse(Layer.Accessory in layers)
        assertFalse(Layer.OnePiece in layers)
        assertTrue(vm.state.value.composerSlots.all { it.selectedItemId == null && !it.isLocked })
    }

    // ---- enhance (composer AI) ----

    @Test
    fun `enhance applies the first suggestion to the slots and opens the swiper for the rest`() = runTest {
        val images = listOf(item("top-1", "tops"), item("top-2", "tops"), item("pants-1", "bottoms"))
        val vm = vm()
        vm.openComposer(seedItemIds = emptySet(), images = images, prefs = null)
        val topSlot = vm.state.value.composerSlots.first { it.category == Layer.Top }
        val bottomSlot = vm.state.value.composerSlots.first { it.category == Layer.Bottom }
        gemini.generateResult = """
            {"suggestions":[
              {"slots":[{"slotId":"${topSlot.id}","itemId":"top-1"},{"slotId":"${bottomSlot.id}","itemId":"pants-1"}],
               "name":"Look A","description":"desc A","reason":"why A","tags":["casual"]},
              {"slots":[{"slotId":"${topSlot.id}","itemId":"top-2"}],"name":"Look B"}
            ]}
        """.trimIndent()

        vm.enhanceComposerWithAi(prefs = null, weather = null, images = images)

        val s = vm.state.first { !it.isComposerEnhancing && it.composerSuggestions.isNotEmpty() }
        assertEquals(2, s.composerSuggestions.size)
        assertEquals("Look A", s.composerAiSuggestedName)
        assertEquals(listOf("casual"), s.composerAiSuggestedTags)
        assertEquals("top-1", s.composerSlots.first { it.id == topSlot.id }.selectedItemId)
        assertEquals("pants-1", s.composerSlots.first { it.id == bottomSlot.id }.selectedItemId)
        assertTrue(s.composerSuggestionsViewerOpen) // more than one option
        assertNotNull(aiRetry.action)
    }

    @Test
    fun `a locked filled slot survives switching suggestions`() = runTest {
        val images = listOf(item("top-1", "tops"), item("top-2", "tops"), item("pants-1", "bottoms"))
        val vm = vm()
        vm.openComposer(seedItemIds = emptySet(), images = images, prefs = null)
        val topSlot = vm.state.value.composerSlots.first { it.category == Layer.Top }
        vm.setSlotItem(topSlot.id, "top-1") // user pick — sets the lock
        gemini.generateResult = """
            {"suggestions":[
              {"slots":[{"slotId":"${topSlot.id}","itemId":"top-2"}],"name":"A"},
              {"slots":[{"slotId":"${topSlot.id}","itemId":"top-2"}],"name":"B"}
            ]}
        """.trimIndent()
        vm.enhanceComposerWithAi(prefs = null, weather = null, images = images)
        vm.state.first { !it.isComposerEnhancing && it.composerSuggestions.isNotEmpty() }

        vm.showComposerSuggestionAt(1)

        assertEquals("top-1", vm.state.value.composerSlots.first { it.id == topSlot.id }.selectedItemId)
    }

    @Test
    fun `a null enhance response surfaces the composer error`() = runTest {
        val images = listOf(item("top-1", "tops"))
        val vm = vm()
        vm.openComposer(seedItemIds = emptySet(), images = images, prefs = null)

        vm.enhanceComposerWithAi(prefs = null, weather = null, images = images)

        val s = vm.state.first { it.composerError != null }
        assertFalse(s.isComposerEnhancing)
        assertEquals("Gemini did not respond.", s.composerError)
    }

    @Test
    fun `an unparseable enhance response surfaces the parse error`() = runTest {
        val images = listOf(item("top-1", "tops"))
        val vm = vm()
        vm.openComposer(seedItemIds = emptySet(), images = images, prefs = null)
        gemini.generateResult = "sorry, no json today"

        vm.enhanceComposerWithAi(prefs = null, weather = null, images = images)

        val s = vm.state.first { it.composerError != null }
        assertEquals("Could not parse Gemini response.", s.composerError)
    }

    @Test
    fun `insufficient credits reset the enhance without a local error (global dialog owns it)`() = runTest {
        val images = listOf(item("top-1", "tops"))
        val vm = vm()
        vm.openComposer(seedItemIds = emptySet(), images = images, prefs = null)
        gemini.outcome = AiResult.InsufficientCredits(needed = 10, have = 2)

        vm.enhanceComposerWithAi(prefs = null, weather = null, images = images)

        val s = vm.state.first { !it.isComposerEnhancing }
        assertNull(s.composerError)
        assertTrue(s.composerSuggestions.isEmpty())
    }

    // ---- save funnels ----

    @Test
    fun `commitOutfit create saves through the repo funnel and closes the composer`() = runTest {
        drive.filesByFolder["f1"] = listOf(DriveFileDto(id = "top-1", name = "top-1_cutout.webp"))
        repo.setScope(listOf("f1"))
        repo.saveFolderId = "f1"
        val images = listOf(item("top-1", "tops"))
        val vm = vm()
        vm.openComposer(seedItemIds = setOf("top-1"), images = images, prefs = null)
        var done: Boolean? = null

        vm.commitOutfit(name = "New look", description = "d", tags = listOf("casual")) { done = it }

        vm.state.first { it.composerItemIds.isEmpty() } // closeComposer ran
        assertEquals(true, done)
        val saved = outfitStore.outfitsFor("f1").single()
        assertEquals("New look", saved.name)
        assertEquals(listOf("top-1"), saved.itemIds)
        assertEquals(listOf("top-1_cutout.webp"), saved.itemNames)
        assertEquals("f1", saved.folderId)
        assertEquals(listOf(OUTFIT_FOLDER_SYNC_KIND), mutationStore.rows.map { it.kind })
        assertEquals(OutfitsEvent.ScrollToOutfit(saved.id), repo.events.first())
        assertNull(vm.state.value.composerEditingOutfitId)
    }

    @Test
    fun `commitOutfit edit updates in place and offers wear-it-now on the repo`() = runTest {
        drive.filesByFolder["f1"] = listOf(
            DriveFileDto(id = "top-1", name = "top-1_cutout.webp"),
            DriveFileDto(id = "pants-1", name = "pants-1_cutout.webp"),
        )
        outfitStore.replaceFolder(
            "f1",
            listOf(Outfit(id = "o-1", name = "Old", itemIds = listOf("top-1"), itemNames = listOf("top-1_cutout.webp"), folderId = "f1")),
        )
        repo.setScope(listOf("f1"))
        repo.saveFolderId = "f1"
        val images = listOf(item("top-1", "tops"), item("pants-1", "bottoms"))
        val vm = vm()
        val existing = vm.state.first { it.outfits.isNotEmpty() }.outfits.single()
        vm.startEditing(existing, images, prefs = null)
        var done: Boolean? = null

        vm.commitOutfit(name = "Renamed", description = "", tags = emptyList()) { done = it }

        vm.state.first { it.composerEditingOutfitId == null } // closeComposer ran
        assertEquals(true, done)
        val stored = outfitStore.outfitsFor("f1").single()
        assertEquals("Renamed", stored.name)
        assertEquals("f1", stored.folderId)
        assertEquals("o-1", repo.pendingWearOutfitId.value)
    }

    // ---- prediction ----

    @Test
    fun `prediction keeps only suggestions matching known styles, first becomes current`() = runTest {
        outfitStore.replaceFolder(
            "f1",
            listOf(
                Outfit(id = "a", name = "A", folderId = "f1"),
                Outfit(id = "b", name = "B", folderId = "f1"),
            ),
        )
        repo.setScope(listOf("f1"))
        val vm = vm()
        vm.state.first { it.outfits.size == 2 }
        gemini.generateResult = """
            {"suggestions":[
              {"outfitId":"a","reason":"r-a"},
              {"outfitId":"ghost","reason":"r-ghost"},
              {"outfitId":"b","reason":"r-b"}
            ]}
        """.trimIndent()

        vm.doTriggerPrediction(prefs = null, weather = null, images = emptyList(), feedbackHistory = emptyList())

        val s = vm.state.first { !it.isPredicting && it.predictionSuggestions.isNotEmpty() }
        assertEquals(listOf("a", "b"), s.predictionSuggestions.map { it.outfitId })
        assertEquals("a", s.prediction?.outfitId)
        assertNull(s.predictionError)
        assertNotNull(aiRetry.action)
    }

    @Test
    fun `prediction refuses without any saved styles`() = runTest {
        repo.setScope(listOf("f1"))
        val vm = vm()

        vm.doTriggerPrediction(prefs = null, weather = null, images = emptyList(), feedbackHistory = emptyList())

        assertEquals("No styles to choose from yet.", vm.state.value.predictionError)
        assertTrue(gemini.generatedPrompts.isEmpty())
    }
}
