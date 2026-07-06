package com.librelookai.tryon

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.librelookai.data.drive.MutationOutcome
import com.librelookai.data.local.PendingMutation
import com.librelookai.data.model.TryOn
import com.librelookai.testing.FakeDriveService
import com.librelookai.testing.FakeTryOnStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Queued-mutation invariants for [TryOnIndexSyncHandler] (refactor § 2 — the last converted
 * metadata write): the payload-free store re-read at apply time, the deliberate absence of a
 * `hasFolder`-style wipe guard (a queued sync implies the store is the local truth, so an
 * empty list is a legitimate write), and retry on Drive failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TryOnSyncHandlersTest {

    private val gson = Gson()
    private val drive = FakeDriveService()
    private val store = FakeTryOnStore()

    private fun mutation() =
        PendingMutation(1L, TRYON_INDEX_SYNC_KIND, "_tryons", null, "{}", null, 0, null, 0L)

    private fun tryOn(imageDriveId: String) = TryOn(
        id = "tryon-$imageDriveId",
        imageDriveId = imageDriveId,
        imageName = "$imageDriveId.png",
        createdAt = 0L,
        sourceOutfitId = "outfit-1",
        sourceKind = "outfit",
    )

    private fun indexJson(): List<TryOn> = gson.fromJson(
        drive.tryOnsJsonByRoot[drive.rootFolderId],
        object : TypeToken<List<TryOn>>() {}.type,
    )

    @Test
    fun `index sync serializes the store's current list at apply time`() = runTest {
        store.replaceAll(listOf(tryOn("a"), tryOn("b")))
        val handler = TryOnIndexSyncHandler(drive, store)

        val outcome = handler.apply(mutation())

        assertEquals(MutationOutcome.Success, outcome)
        assertEquals(listOf("a", "b"), indexJson().map { it.imageDriveId })
    }

    @Test
    fun `index sync of an empty store writes an empty index (no wipe guard, deliberate)`() = runTest {
        val handler = TryOnIndexSyncHandler(drive, store)

        val outcome = handler.apply(mutation())

        assertEquals(MutationOutcome.Success, outcome)
        assertEquals(emptyList<TryOn>(), indexJson())
    }

    @Test
    fun `index sync retries on a drive failure`() = runTest {
        store.replaceAll(listOf(tryOn("a")))
        val failingDrive = object : FakeDriveService() {
            override suspend fun saveTryOnsJson(rootFolderId: String, json: String) =
                error("drive down")
        }
        val handler = TryOnIndexSyncHandler(failingDrive, store)

        val outcome = handler.apply(mutation())

        assertTrue(outcome is MutationOutcome.Retry)
        assertTrue(failingDrive.tryOnsJsonByRoot.isEmpty())
    }
}
