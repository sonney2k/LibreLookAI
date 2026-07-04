package com.librelookai.wardrobe

import com.google.gson.Gson
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.MutationOutcome
import com.librelookai.data.local.CachedWardrobeItem
import com.librelookai.data.local.PendingMutation
import com.librelookai.gemini.ClothingTags
import com.librelookai.testing.FakeDriveService
import com.librelookai.testing.FakeWardrobeItemStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Queued-mutation invariants for the wardrobe sync handlers (refactor § 3 slice 2 / § 8):
 * drain / retry / rollback over [FakeDriveService] + [FakeWardrobeItemStore] — the § 2 handler
 * rules (payload-free re-reads, best-effort sub-files, rollback-only-if-still-in-target)
 * become tested invariants instead of doc prose.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WardrobeSyncHandlersTest {

    private val gson = Gson()
    private val drive = FakeDriveService()
    private val items = FakeWardrobeItemStore()

    private fun mutation(kind: String, targetId: String, payload: String = "") =
        PendingMutation(1L, kind, targetId, null, payload, null, 0, null, 0L)

    private fun item(driveId: String, sidecarId: String? = null) = CachedWardrobeItem(
        driveId = driveId,
        name = "${driveId}_cutout.webp",
        tags = ClothingTags(label = "Blue tee", type = "t-shirt"),
        originalDriveId = "$driveId-orig",
        sidecarDriveId = sidecarId,
    )

    // ---------- sidecar sync ----------

    @Test
    fun `sidecar sync writes the row's current tags into the owning folder and stamps the id`() = runTest {
        items.addAll("folder-1", listOf(item("item-1")))
        val handler = WardrobeSidecarSyncHandler(drive, items)

        val outcome = handler.apply(mutation(SIDECAR_SYNC_KIND, "item-1"))

        assertEquals(MutationOutcome.Success, outcome)
        val row = items.find("item-1")!!.second
        val expected = gson.toJson(ItemSidecar(row.tags, row.originalDriveId))
        assertEquals(
            expected,
            drive.upsertedSidecars["folder-1/item-1${DriveRepository.SIDECAR_SUFFIX}"],
        )
        assertEquals("sidecar-item-1${DriveRepository.SIDECAR_SUFFIX}", row.sidecarDriveId)
    }

    @Test
    fun `sidecar sync of a deleted item is permanent and writes nothing`() = runTest {
        val handler = WardrobeSidecarSyncHandler(drive, items)

        val outcome = handler.apply(mutation(SIDECAR_SYNC_KIND, "gone"))

        assertTrue(outcome is MutationOutcome.Permanent)
        assertTrue(drive.upsertedSidecars.isEmpty())
    }

    @Test
    fun `sidecar sync retries on a drive failure`() = runTest {
        items.addAll("folder-1", listOf(item("item-1")))
        val failingDrive = object : FakeDriveService() {
            override suspend fun upsertSidecar(folderId: String, name: String, json: String): String =
                error("drive down")
        }
        val handler = WardrobeSidecarSyncHandler(failingDrive, items)

        val outcome = handler.apply(mutation(SIDECAR_SYNC_KIND, "item-1"))

        assertTrue(outcome is MutationOutcome.Retry)
        assertNull(items.find("item-1")!!.second.sidecarDriveId)
    }

    // ---------- delete ----------

    @Test
    fun `delete removes every payload file id`() = runTest {
        val handler = WardrobeDeleteSyncHandler(drive)
        val payload = gson.toJson(DeleteItemPayload(listOf("cutout", "orig", "sidecar")))

        val outcome = handler.apply(mutation(ITEM_DELETE_KIND, "cutout", payload))

        assertEquals(MutationOutcome.Success, outcome)
        assertEquals(listOf("cutout", "orig", "sidecar"), drive.deletedFileIds)
    }

    @Test
    fun `delete retries on a drive failure`() = runTest {
        val failingDrive = object : FakeDriveService() {
            override suspend fun deleteFile(fileId: String) = error("drive down")
        }
        val handler = WardrobeDeleteSyncHandler(failingDrive)
        val payload = gson.toJson(DeleteItemPayload(listOf("cutout")))

        val outcome = handler.apply(mutation(ITEM_DELETE_KIND, "cutout", payload))

        assertTrue(outcome is MutationOutcome.Retry)
    }

    @Test
    fun `delete with an unparseable payload is permanent`() = runTest {
        val handler = WardrobeDeleteSyncHandler(drive)

        val outcome = handler.apply(mutation(ITEM_DELETE_KIND, "cutout", "{not json"))

        assertTrue(outcome is MutationOutcome.Permanent)
        assertTrue(drive.deletedFileIds.isEmpty())
    }

    // ---------- move ----------

    private fun movePayload(sidecarDriveId: String? = "sidecar-1") = gson.toJson(
        MoveItemPayload(
            sourceFolderId = "src",
            targetFolderId = "dst",
            originalDriveId = "orig-1",
            sidecarDriveId = sidecarDriveId,
        ),
    )

    @Test
    fun `move replays the parent patch for the whole triplet`() = runTest {
        items.addAll("dst", listOf(item("item-1")))
        val handler = WardrobeMoveSyncHandler(drive, items)

        val outcome = handler.apply(mutation(ITEM_MOVE_KIND, "item-1", movePayload()))

        assertEquals(MutationOutcome.Success, outcome)
        assertEquals(
            listOf(
                Triple("item-1", "src", "dst"),
                Triple("orig-1", "src", "dst"),
                Triple("sidecar-1", "src", "dst"),
            ),
            drive.movedFiles,
        )
    }

    @Test
    fun `move of a deleted item is permanent and moves nothing`() = runTest {
        val handler = WardrobeMoveSyncHandler(drive, items)

        val outcome = handler.apply(mutation(ITEM_MOVE_KIND, "gone", movePayload()))

        assertTrue(outcome is MutationOutcome.Permanent)
        assertTrue(drive.movedFiles.isEmpty())
    }

    @Test
    fun `move retries when the cutout patch fails`() = runTest {
        items.addAll("dst", listOf(item("item-1")))
        val failingDrive = object : FakeDriveService() {
            override suspend fun moveFile(fileId: String, fromFolderId: String, toFolderId: String) =
                error("drive down")
        }
        val handler = WardrobeMoveSyncHandler(failingDrive, items)

        val outcome = handler.apply(mutation(ITEM_MOVE_KIND, "item-1", movePayload()))

        assertTrue(outcome is MutationOutcome.Retry)
    }

    @Test
    fun `original and sidecar moves stay best-effort`() = runTest {
        items.addAll("dst", listOf(item("item-1")))
        val failingDrive = object : FakeDriveService() {
            override suspend fun moveFile(fileId: String, fromFolderId: String, toFolderId: String) {
                if (fileId != "item-1") error("drive down")
                super.moveFile(fileId, fromFolderId, toFolderId)
            }
        }
        val handler = WardrobeMoveSyncHandler(failingDrive, items)

        val outcome = handler.apply(mutation(ITEM_MOVE_KIND, "item-1", movePayload()))

        assertEquals(MutationOutcome.Success, outcome)
        assertEquals(listOf(Triple("item-1", "src", "dst")), failingDrive.movedFiles)
    }

    @Test
    fun `rollback re-homes the row to the source only while it still sits in this move's target`() = runTest {
        items.addAll("dst", listOf(item("item-1")))
        val handler = WardrobeMoveSyncHandler(drive, items)
        val rollbacks = mutableListOf<MoveRollback>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            handler.moveRolledBack.collect { rollbacks += it }
        }

        handler.rollback(mutation(ITEM_MOVE_KIND, "item-1", movePayload()))

        assertEquals("src", items.find("item-1")!!.first)
        assertEquals(listOf(MoveRollback("item-1", "src", "dst")), rollbacks)
    }

    @Test
    fun `rollback is a no-op when a later move already re-homed the row`() = runTest {
        items.addAll("elsewhere", listOf(item("item-1")))
        val handler = WardrobeMoveSyncHandler(drive, items)
        val rollbacks = mutableListOf<MoveRollback>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            handler.moveRolledBack.collect { rollbacks += it }
        }

        handler.rollback(mutation(ITEM_MOVE_KIND, "item-1", movePayload()))

        assertEquals("elsewhere", items.find("item-1")!!.first)
        assertTrue(rollbacks.isEmpty())
    }
}
