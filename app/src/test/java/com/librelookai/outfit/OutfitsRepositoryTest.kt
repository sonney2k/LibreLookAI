package com.librelookai.outfit

import com.google.gson.Gson
import com.librelookai.data.drive.DrainScheduler
import com.librelookai.data.drive.DriveFileDto
import com.librelookai.data.drive.SyncEngine
import com.librelookai.data.local.CachedWardrobeItem
import com.librelookai.data.model.Outfit
import com.librelookai.testing.FakeDriveService
import com.librelookai.testing.FakeMutationStore
import com.librelookai.testing.FakeOutfitStore
import com.librelookai.testing.FakeWardrobeItemStore
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [OutfitsRepository] tests (refactor § 5 slice 8 / § 8). Two layers:
 *  - Pure decision logic ([resolveOutfits], [foldersToWrite], [outfitsHomedIn]) as plain unit
 *    tests — the extension-agnostic `itemNames` → `itemIds` resolution and the single-home rule.
 *  - Fake-based repository tests over [FakeDriveService] + the in-memory store fakes (the
 *    `TripsRepositoryTest` pattern, unlocked by the § 3 `DriveService` seam): the store-derived
 *    [OutfitsRepository.outfits]/[OutfitsRepository.wardrobeImages] flows, the Drive reconcile's
 *    cross-closet resolution, and the [OutfitsRepository.saveOutfit] /
 *    [OutfitsRepository.persistOutfitFolders] local-first funnel become tested invariants.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutfitsRepositoryTest {

    private val gson = Gson()
    private val drive = FakeDriveService()
    private val itemStore = FakeWardrobeItemStore()
    private val outfitStore = FakeOutfitStore()
    private val mutationStore = FakeMutationStore()
    // No handlers registered: drain() halts on the unknown kind, leaving the queued rows
    // observable (the § 2 engine contract — unknown kinds halt without data loss).
    private val syncEngine = SyncEngine(mutationStore, emptySet(), object : DrainScheduler {
        override fun ensureScheduled() {}
    })

    private fun repo() = OutfitsRepository(drive, itemStore, outfitStore, mutationStore, syncEngine)

    private fun outfit(
        id: String,
        itemNames: List<String> = emptyList(),
        itemIds: List<String> = emptyList(),
        folderId: String = "",
    ) = Outfit(id = id, name = "Outfit $id", itemNames = itemNames, itemIds = itemIds, folderId = folderId)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- resolveOutfits ----

    @Test
    fun `resolveOutfits resolves itemNames to ids and stamps the folder`() {
        val nameToId = mapOf("shirt_cutout" to "id-shirt", "pants_cutout" to "id-pants")
        val raw = listOf(outfit("a", itemNames = listOf("shirt_cutout.webp", "pants_cutout.webp")))

        val resolved = resolveOutfits(raw, "closet-1", nameToId)

        assertEquals(listOf("id-shirt", "id-pants"), resolved.single().itemIds)
        assertEquals("closet-1", resolved.single().folderId)
    }

    @Test
    fun `resolveOutfits matches a legacy png itemName against a webp item (extension-agnostic)`() {
        // The live item is now `{id}_cutout.webp`; the outfit JSON still holds the old `.png` name.
        // itemMatchKey normalises both to `{id}_cutout`, so it still resolves after the WebP rename.
        val nameToId = mapOf("xyz_cutout" to "id-xyz")
        val raw = listOf(outfit("a", itemNames = listOf("xyz_cutout.png")))

        assertEquals(listOf("id-xyz"), resolveOutfits(raw, "f", nameToId).single().itemIds)
    }

    @Test
    fun `resolveOutfits drops itemNames with no matching live item`() {
        val raw = listOf(outfit("a", itemNames = listOf("gone_cutout.webp", "here_cutout.webp")))
        val nameToId = mapOf("here_cutout" to "id-here")

        assertEquals(listOf("id-here"), resolveOutfits(raw, "f", nameToId).single().itemIds)
    }

    @Test
    fun `resolveOutfits keeps existing itemIds when there are no itemNames`() {
        val raw = listOf(outfit("a", itemNames = emptyList(), itemIds = listOf("kept-1", "kept-2")))

        assertEquals(listOf("kept-1", "kept-2"), resolveOutfits(raw, "f", emptyMap()).single().itemIds)
    }

    // ---- foldersToWrite ----

    @Test
    fun `foldersToWrite unions scope and affected and drops blanks`() {
        assertEquals(
            setOf("a", "b", "c"),
            foldersToWrite(scope = listOf("a", "b"), affected = listOf("b", "c")),
        )
        assertEquals(setOf("a"), foldersToWrite(scope = listOf("a", ""), affected = listOf("")))
    }

    // ---- outfitsHomedIn ----

    @Test
    fun `outfitsHomedIn keeps homed plus not-yet-homed outfits and excludes other folders`() {
        val styles = listOf(
            outfit("homed", folderId = "f1"),
            outfit("other", folderId = "f2"),
            outfit("fresh", folderId = ""), // not yet homed — rides into every folder until replaceFolder resolves it
        )

        assertEquals(setOf("homed", "fresh"), outfitsHomedIn("f1", styles).map { it.id }.toSet())
        assertEquals(setOf("other", "fresh"), outfitsHomedIn("f2", styles).map { it.id }.toSet())
    }

    // ---- Derived flows (§ 5 slice 4b) ----

    @Test
    fun `outfits derives the scoped folders' store rows`() = runTest {
        outfitStore.replaceFolder("f1", listOf(outfit("a", folderId = "f1")))
        outfitStore.replaceFolder("f2", listOf(outfit("b", folderId = "f2")))
        outfitStore.replaceFolder("elsewhere", listOf(outfit("c", folderId = "elsewhere")))
        val repo = repo()
        repo.setScope(listOf("f1", "f2"))

        val outfits = repo.outfits.first { it.isNotEmpty() }

        assertEquals(setOf("a", "b"), outfits.map { it.id }.toSet())
        assertEquals(setOf("a", "b"), repo.cachedOutfits().map { it.id }.toSet())
    }

    @Test
    fun `wardrobeImages derives locally-cached items only and stamps the owning folder`() = runTest {
        drive.cached["cached"] = File(drive.cacheDir, "cached").apply { writeText("bytes") }
        itemStore.replaceFolder(
            "f1",
            listOf(
                CachedWardrobeItem(driveId = "cached", name = "cached_cutout.webp", tags = null),
                CachedWardrobeItem(driveId = "uncached", name = "uncached_cutout.webp", tags = null),
            ),
        )
        val repo = repo()
        repo.setScope(listOf("f1"))

        val images = repo.wardrobeImages.first { it.isNotEmpty() }

        assertEquals(listOf("cached"), images.map { it.driveId })
        assertEquals("f1", images.single().folderId)
    }

    // ---- Phase-2 Drive reconcile ----

    @Test
    fun `syncOutfitsFromDrive resolves itemNames through the combined cross-closet map`() = runTest {
        drive.filesByFolder["f1"] = listOf(DriveFileDto(id = "id-shirt", name = "shirt_cutout.webp"))
        drive.filesByFolder["f2"] = listOf(DriveFileDto(id = "id-pants", name = "pants_cutout.webp"))
        // f1's outfit references an item that moved to f2 — by its legacy `.png` name, no less.
        drive.outfitsJsonByFolder["f1"] =
            gson.toJson(listOf(outfit("a", itemNames = listOf("shirt_cutout.webp", "pants_cutout.png"))))
        val repo = repo()
        repo.setScope(listOf("f1", "f2"))

        val result = repo.syncOutfitsFromDrive()

        assertTrue(result.isSuccess)
        val stored = outfitStore.outfitsFor("f1").single()
        assertEquals(listOf("id-shirt", "id-pants"), stored.itemIds)
        assertEquals("f1", stored.folderId)
        assertEquals(emptyList<Outfit>(), outfitStore.outfitsFor("f2"))
    }

    // ---- saveOutfit / persistOutfitFolders (the § 2 local-first funnel) ----

    @Test
    fun `saveOutfit stamps the home folder, resolves itemNames and rides the queue`() = runTest {
        drive.filesByFolder["f2"] = listOf(DriveFileDto(id = "id-shirt", name = "shirt_cutout.webp"))
        val repo = repo()
        repo.setScope(listOf("f1", "f2"))
        repo.saveFolderId = "f2"

        val saved = repo.saveOutfit(name = "New", description = "", itemIds = listOf("id-shirt"))!!

        assertEquals("f2", saved.folderId)
        assertEquals(listOf("shirt_cutout.webp"), saved.itemNames)
        assertEquals(listOf(saved.id), outfitStore.outfitsFor("f2").map { it.id })
        // One payload-free folder sync for the affected save target…
        assertEquals(listOf(OUTFIT_FOLDER_SYNC_KIND), mutationStore.rows.map { it.kind })
        assertEquals("f2", mutationStore.rows.single().folderId)
        // …and the scroll one-shot the list surface consumes.
        assertEquals(OutfitsEvent.ScrollToOutfit(saved.id), repo.events.first())
    }

    @Test
    fun `saveOutfit is null with no items or no resolvable save target`() = runTest {
        val repo = repo()
        repo.setScope(listOf("f1"))
        repo.saveFolderId = "f1"
        assertNull(repo.saveOutfit("n", "", itemIds = emptyList()))

        repo.saveFolderId = null // no closet session yet — setScope keeps folderId null
        assertNull(repo.saveOutfit("n", "", itemIds = listOf("id-shirt")))

        assertEquals(0, mutationStore.rows.size)
    }

    @Test
    fun `persistOutfitFolders homes each outfit into its folder and queues one sync per affected`() = runTest {
        val repo = repo()
        repo.setScope(listOf("f1", "f2"))
        val styles = listOf(outfit("a", folderId = "f1"), outfit("b", folderId = "f2"))

        repo.persistOutfitFolders(styles, affected = listOf("f1", "f2"))

        assertEquals(listOf("a"), outfitStore.outfitsFor("f1").map { it.id })
        assertEquals(listOf("b"), outfitStore.outfitsFor("f2").map { it.id })
        assertEquals(
            listOf(OUTFIT_FOLDER_SYNC_KIND, OUTFIT_FOLDER_SYNC_KIND),
            mutationStore.rows.map { it.kind },
        )
        assertEquals(setOf("f1", "f2"), mutationStore.rows.map { it.folderId }.toSet())
    }

    @Test
    fun `a fresh empty-folderId outfit lands in exactly one folder after persist`() = runTest {
        val repo = repo()
        repo.setScope(listOf("f1", "f2"))

        // Legacy path: a fresh outfit not yet stamped with a home rides into every folder's
        // write; the store's outfit-id primary key resolves the single final home.
        repo.persistOutfitFolders(listOf(outfit("fresh", folderId = "")), affected = emptyList())

        val homes = listOf("f1", "f2").count { outfitStore.outfitsFor(it).any { o -> o.id == "fresh" } }
        assertEquals(1, homes)
    }
}
