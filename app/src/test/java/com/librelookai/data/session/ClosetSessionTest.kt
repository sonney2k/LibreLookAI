package com.librelookai.data.session

import com.librelookai.data.model.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain-JUnit tests for the derived closet-scope rules that used to live inline in AppContent's
 * fan-out LaunchedEffect (refactor § 5 slice 1): active-closet resolution, the cross-closet
 * similarity-snapshot scope, and the default-import / save-folder fallbacks.
 */
class ClosetSessionTest {

    private val home = Location(name = "Home", folderId = "fHome")
    private val office = Location(name = "Office", folderId = "fOffice")
    private val session = ClosetSession(locations = listOf(home, office))

    @Test
    fun `activeFolderId is null for All-locations and unknown ids`() {
        assertNull(session.activeFolderId) // defaults to ALL_LOCATIONS_ID
        assertNull(session.copy(activeLocationId = "gone").activeFolderId)
        assertNull(session.copy(activeLocationId = "").activeFolderId)
        assertEquals("fOffice", session.copy(activeLocationId = "fOffice").activeFolderId)
    }

    @Test
    fun `snapshot scope appends the shopping folder once known`() {
        assertEquals(listOf("fHome", "fOffice"), session.snapshotFolderIds)
        assertEquals(
            listOf("fHome", "fOffice", "fShop"),
            session.copy(shoppingFolderId = "fShop").snapshotFolderIds,
        )
    }

    @Test
    fun `default import folder falls back to the first location`() {
        assertEquals("fHome", session.defaultImportFolderId) // nothing persisted yet
        assertEquals("fOffice", session.copy(defaultClosetFolderId = "fOffice").defaultImportFolderId)
        // A stale persisted default (closet since deleted) falls back too.
        assertEquals("fHome", session.copy(defaultClosetFolderId = "gone").defaultImportFolderId)
        assertNull(ClosetSession().defaultImportFolderId)
    }

    @Test
    fun `save folder prefers the active closet`() {
        assertEquals("fHome", session.saveFolderId)
        assertEquals("fOffice", session.copy(activeLocationId = "fOffice").saveFolderId)
        assertNull(ClosetSession().saveFolderId)
    }

    @Test
    fun `holder merges the two publishers' updates`() {
        val holder = ClosetSessionHolder()
        holder.setShoppingFolder("fShop")
        holder.setClosets(listOf(home), activeLocationId = "fHome", defaultClosetFolderId = null)

        val s = holder.session.value
        assertEquals("fShop", s.shoppingFolderId) // setClosets must not clobber it
        assertEquals(listOf(home), s.locations)
        assertEquals("fHome", s.activeLocationId)
    }
}
