package com.librelookai.wardrobe

import androidx.test.core.app.ApplicationProvider
import com.librelookai.data.drive.DrainScheduler
import com.librelookai.data.drive.SyncEngine
import com.librelookai.data.local.CachedWardrobeItem
import com.librelookai.data.session.UserPreferencesRepository
import com.librelookai.gemini.AiResult
import com.librelookai.gemini.ClothingTags
import com.librelookai.service.JobLock
import com.librelookai.testing.FakeAiClient
import com.librelookai.testing.FakeDriveService
import com.librelookai.testing.FakeMutationStore
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fake-based suite for the per-item maintenance ops singleton (refactor § 8 — the § 5 slice 9
 * VM-slimming extraction): the store-first tag funnel over the § 2 sidecar queue, the
 * reprocess-bg byte replacement + version bump, the credits-exhausted and missing-original
 * degrade paths, the silent rotate upload pair and the original-cache resolution.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WardrobeItemOpsTest {

    private val dispatcher = StandardTestDispatcher()
    private val drive = FakeDriveService()
    private val ai = FakeAiClient()
    private val items = FakeWardrobeItemStore()
    private val mutations = FakeMutationStore()
    private val engine = SyncEngine(mutations, emptySet(), object : DrainScheduler {
        override fun ensureScheduled() {}
    })
    private val sidecarSync = SidecarSyncQueue(items, mutations, engine)
    private val versions = ItemVersions()

    // Main-dispatcher scope inside — built lazily, after setMain.
    private fun ops() = WardrobeItemOps(
        ApplicationProvider.getApplicationContext(),
        drive, ai, items, sidecarSync, versions,
        JobLock(ApplicationProvider.getApplicationContext()),
        UserPreferencesRepository(),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun row(driveId: String, originalDriveId: String? = null) {
        items.addAll(
            "f1",
            listOf(
                CachedWardrobeItem(
                    driveId = driveId,
                    name = "${driveId}_cutout.webp",
                    tags = null,
                    originalDriveId = originalDriveId,
                ),
            ),
        )
    }

    private fun cacheFile(name: String): File =
        File(drive.cacheDir, name).apply { writeText("bytes") }

    /** The ops hop to the real [Dispatchers.IO] mid-run — same wait as the bulk-use-case suite. */
    private suspend fun awaitUntil(condition: () -> Boolean) {
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            val deadline = System.currentTimeMillis() + 5_000
            while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        }
    }

    // ---------- Tag writes ----------

    @Test
    fun `updateTags writes the store row and enqueues one sidecar sync`() = runTest(dispatcher) {
        row("i1")

        ops().updateTags("i1", ClothingTags(label = "Red dress", type = "dress"))
        advanceUntilIdle()
        awaitUntil { mutations.rows.isNotEmpty() }

        assertEquals("dress", items.find("i1")!!.second.tags?.type)
        assertEquals(listOf(SIDECAR_SYNC_KIND to "i1"), mutations.rows.map { it.kind to it.targetId })
        assertTrue(ai.classifiedFiles.isEmpty()) // a manual edit never calls Gemini
    }

    @Test
    fun `tagImage classifies the cached file then persists store-first and enqueues`() = runTest(dispatcher) {
        row("i1")
        drive.cached["i1"] = cacheFile("i1.png")
        ai.classifyResult = ClothingTags(label = "Blue tee", type = "t-shirt")

        val ops = ops()
        ops.tagImage("i1")
        advanceUntilIdle()
        awaitUntil { mutations.rows.isNotEmpty() }

        assertEquals(listOf(drive.cached["i1"]), ai.classifiedFiles)
        assertEquals("t-shirt", items.find("i1")!!.second.tags?.type)
        assertEquals(listOf(SIDECAR_SYNC_KIND to "i1"), mutations.rows.map { it.kind to it.targetId })
        assertNull(ops.progress.value.processingImageId)
    }

    @Test
    fun `tagImage credits exhaustion resets progress and skips the write`() = runTest(dispatcher) {
        row("i1")
        drive.cached["i1"] = cacheFile("i1.png")
        ai.outcome = AiResult.InsufficientCredits(needed = 10, have = 0)

        val ops = ops()
        ops.tagImage("i1")
        advanceUntilIdle()

        assertNull(items.find("i1")!!.second.tags)
        assertEquals(0, mutations.rows.size)
        assertNull(ops.progress.value.processingImageId)
    }

    // ---------- Background reprocess ----------

    @Test
    fun `reprocessBackground replaces the bytes in place and bumps the Coil version`() = runTest(dispatcher) {
        row("i1")
        cacheFile("i1_original.jpg") // canonical archived-original location — resolved first
        ai.removeBackgroundResult = cacheFile("i1_processed.png")

        val ops = ops()
        ops.reprocessBackground("i1")
        advanceUntilIdle()
        awaitUntil { drive.updatedImageIds.isNotEmpty() }
        advanceUntilIdle()

        assertEquals("i1_original.jpg", ai.removedBackgrounds.single().name)
        assertEquals(listOf("i1"), drive.updatedImageIds) // stable Drive ID — PATCH, not re-upload
        assertTrue(File(drive.cacheDir, "i1.png").exists()) // display cache refreshed
        assertEquals(setOf("i1"), versions.versions.value.keys)
        assertNull(ops.progress.value.processingImageId)
    }

    @Test
    fun `reprocessBackground surfaces a missing original as an error and resets`() = runTest(dispatcher) {
        row("i1") // no original cache, no originalDriveId, no cached cutout

        val ops = ops()
        val errors = mutableListOf<String?>()
        val collector = launch { ops.errors.collect { errors += it } }
        ops.reprocessBackground("i1")
        advanceUntilIdle()
        collector.cancel()

        assertTrue(ai.removedBackgrounds.isEmpty())
        assertNotNull(errors.lastOrNull()) // the null clear-emission precedes the message
        assertEquals(ItemOpProgress(), ops.progress.value)
    }

    // ---------- Rotate ----------

    @Test
    fun `rotateImage uploads cutout and original silently after bumping the version`() = runTest(dispatcher) {
        row("i1", originalDriveId = "orig1")
        drive.cached["i1"] = cacheFile("i1.png")
        drive.cached["orig1"] = cacheFile("i1_original.jpg")

        val ops = ops()
        ops.rotateImage("i1")
        advanceUntilIdle()
        awaitUntil { drive.updatedImageIds.size == 2 }

        assertEquals(listOf("i1", "orig1"), drive.updatedImageIds)
        assertEquals(setOf("i1"), versions.versions.value.keys)
        assertNull(ops.progress.value.processingImageId) // silent — no overlay
    }

    // ---------- Original cache resolution ----------

    @Test
    fun `ensureOriginalCached downloads via the row's originalDriveId to the canonical path`() = runTest(dispatcher) {
        row("i1", originalDriveId = "orig1")

        val path = ops().ensureOriginalCached("i1")

        assertEquals(File(drive.cacheDir, "i1_original.jpg").absolutePath, path)
        assertEquals("img-orig1", File(path!!).readText())
    }

    @Test
    fun `ensureOriginalCached returns null for an item without an archived original`() = runTest(dispatcher) {
        row("i1")

        assertNull(ops().ensureOriginalCached("i1"))
    }
}
