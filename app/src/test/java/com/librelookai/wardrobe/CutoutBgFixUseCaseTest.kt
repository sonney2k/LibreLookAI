package com.librelookai.wardrobe

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.librelookai.data.drive.DriveFileDto
import com.librelookai.service.JobLock
import com.librelookai.testing.FakeDriveService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fake-based suite for the cutout background-fix scan → review → apply state machine
 * (refactor § 8 — the § 5 slice 6 use-case behind `FixCutoutBgDialog`). The pixel detection
 * ([com.librelookai.gemini.detectCutoutIssues]) runs for real over tiny Robolectric-native
 * bitmaps — an opaque-black canvas trips the border-ring scan, a green square over
 * transparency trips the edge-halo scan — so the flag → preselect → apply wiring is exercised
 * end-to-end, not scripted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CutoutBgFixUseCaseTest {

    private val dispatcher = StandardTestDispatcher()
    private val drive = FakeDriveService()
    private val versions = ItemVersions()

    private fun useCase() = CutoutBgFixUseCase(
        drive, versions, JobLock(ApplicationProvider.getApplicationContext()),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The use-case runs on the real [Dispatchers.IO] — same wait as the sibling suites. */
    private suspend fun awaitUntil(condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            val deadline = System.currentTimeMillis() + 5_000
            while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        }
    }

    /** Writes a real 20×20 PNG into the fake's cache and registers it under [driveId]. */
    private fun cachedPng(driveId: String, draw: (Bitmap) -> Unit): File {
        val bmp = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        draw(bmp)
        val f = File(drive.cacheDir, "src-$driveId.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        drive.cached[driveId] = f
        return f
    }

    private fun blackBackground(bmp: Bitmap) = bmp.eraseColor(Color.BLACK)

    private fun greenHalo(bmp: Bitmap) {
        bmp.eraseColor(Color.TRANSPARENT)
        for (x in 6..13) for (y in 6..13) bmp.setPixel(x, y, Color.GREEN)
    }

    private fun clean(bmp: Bitmap) = bmp.eraseColor(Color.WHITE)

    private fun folderWithCutouts(folderId: String, vararg ids: String) {
        drive.allImageFilesByFolder[folderId] =
            ids.map { DriveFileDto(id = it, name = "${it}_cutout.png") }
    }

    // ---------- Scan ----------

    @Test
    fun `scan flags black-background and green-halo cutouts and preselects them`() = runTest(dispatcher) {
        cachedPng("black", ::blackBackground)
        cachedPng("halo", ::greenHalo)
        cachedPng("ok", ::clean)
        folderWithCutouts("f1", "black", "halo", "ok")

        val uc = useCase()
        uc.startScan(listOf("f1"))
        awaitUntil { uc.progress.value?.awaitingConfirmation == true }

        val s = uc.progress.value!!
        assertEquals(3, s.items.size)
        assertEquals(setOf("black", "halo"), s.flaggedIds)
        assertEquals(setOf("black", "halo"), s.selectedIds) // flagged are preselected
        assertFalse(s.showAll) // something was flagged — clean items stay hidden
        assertTrue(s.applyBlackToAlpha) // a detected issue defaults its action ON
        assertTrue(s.applyDespillGreen)
        val byId = s.items.associateBy { it.driveId }
        assertTrue(byId["black"]!!.hasBlackBackground)
        assertFalse(byId["black"]!!.hasGreenHalo)
        assertTrue(byId["halo"]!!.hasGreenHalo)
        assertFalse(byId["ok"]!!.hasBlackBackground || byId["ok"]!!.hasGreenHalo)
        assertEquals(3, s.scannedCutouts)
    }

    @Test
    fun `a clean scan flags nothing and defaults to show-all`() = runTest(dispatcher) {
        cachedPng("ok", ::clean)
        folderWithCutouts("f1", "ok")

        val uc = useCase()
        uc.startScan(listOf("f1"))
        awaitUntil { uc.progress.value?.awaitingConfirmation == true }

        val s = uc.progress.value!!
        assertTrue(s.flaggedIds.isEmpty())
        assertTrue(s.selectedIds.isEmpty())
        assertTrue(s.showAll) // nothing flagged — show everything so hand-picking stays possible
        assertFalse(s.applyBlackToAlpha)
        assertFalse(s.applyDespillGreen)
    }

    @Test
    fun `an uncached, undownloadable cutout is skipped but still counted as scanned`() = runTest(dispatcher) {
        val failingDrive = object : FakeDriveService() {
            override suspend fun downloadToCache(driveId: String, driveName: String): File? = null
        }
        failingDrive.allImageFilesByFolder["f1"] =
            listOf(DriveFileDto(id = "gone", name = "gone_cutout.png"))

        val uc = CutoutBgFixUseCase(
            failingDrive, versions, JobLock(ApplicationProvider.getApplicationContext()),
        )
        uc.startScan(listOf("f1"))
        awaitUntil { uc.progress.value?.awaitingConfirmation == true }

        // The scan still completes into the review state — a missing file never stalls it —
        // and with nothing scannable the empty review defaults to show-all.
        val s = uc.progress.value!!
        assertTrue(s.items.isEmpty())
        assertTrue(s.showAll)
    }

    // ---------- Review-state edits ----------

    @Test
    fun `selection and action edits only apply while awaiting confirmation`() = runTest(dispatcher) {
        val uc = useCase()

        // Before any scan there is no state to edit.
        uc.toggleSelection("black")
        uc.setAction(blackToAlpha = true)
        assertNull(uc.progress.value)

        cachedPng("black", ::blackBackground)
        folderWithCutouts("f1", "black")
        uc.startScan(listOf("f1"))
        awaitUntil { uc.progress.value?.awaitingConfirmation == true }

        uc.toggleSelection("black") // deselect
        assertTrue(uc.progress.value!!.selectedIds.isEmpty())
        uc.toggleSelection("black") // reselect
        assertEquals(setOf("black"), uc.progress.value!!.selectedIds)
        uc.setSelection(emptySet())
        assertTrue(uc.progress.value!!.selectedIds.isEmpty())
        uc.setAction(despillGreen = true, feather = false)
        assertTrue(uc.progress.value!!.applyDespillGreen)
        assertFalse(uc.progress.value!!.applyFeather)
        assertTrue(uc.progress.value!!.applyBlackToAlpha) // unset params keep their value
    }

    @Test
    fun `cancelling the review clears the state without touching Drive`() = runTest(dispatcher) {
        cachedPng("black", ::blackBackground)
        folderWithCutouts("f1", "black")

        val uc = useCase()
        uc.startScan(listOf("f1"))
        awaitUntil { uc.progress.value?.awaitingConfirmation == true }

        uc.continueFix(process = false)
        assertNull(uc.progress.value)
        assertTrue(drive.updatedImageIds.isEmpty())
    }

    @Test
    fun `confirming with an empty selection also just clears`() = runTest(dispatcher) {
        cachedPng("ok", ::clean)
        folderWithCutouts("f1", "ok")

        val uc = useCase()
        uc.startScan(listOf("f1"))
        awaitUntil { uc.progress.value?.awaitingConfirmation == true }

        uc.continueFix(process = true) // nothing was flagged, so nothing is selected
        assertNull(uc.progress.value)
        assertTrue(drive.updatedImageIds.isEmpty())
    }

    // ---------- Apply ----------

    @Test
    fun `applying uploads the fixed bytes, replaces the local cache and bumps versions`() = runTest(dispatcher) {
        cachedPng("black", ::blackBackground)
        cachedPng("halo", ::greenHalo)
        folderWithCutouts("f1", "black", "halo")

        val uc = useCase()
        uc.startScan(listOf("f1"))
        awaitUntil { uc.progress.value?.awaitingConfirmation == true }
        uc.continueFix(process = true)
        awaitUntil { uc.progress.value?.isDone == true }

        val s = uc.progress.value!!
        assertEquals(2, s.processTotal)
        assertEquals(2, s.processDone)
        assertFalse(s.isProcessing)
        assertEquals(listOf("black", "halo"), drive.updatedImageIds.sorted())
        // The display cache is replaced in place so Coil picks the fix up via the version bump.
        assertTrue(File(drive.cacheDir, "black.png").exists())
        assertTrue(File(drive.cacheDir, "halo.png").exists())
        assertEquals(setOf("black", "halo"), versions.versions.value.keys)
        // The FixCutoutBgDialog's Done button dismisses.
        uc.dismiss()
        assertNull(uc.progress.value)
    }
}
