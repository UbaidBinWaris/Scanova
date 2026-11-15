package app.scanova.feature.documents.files

import app.scanova.core.data.model.Document
import app.scanova.core.data.model.SyncState
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesLogicTest {

    private val receipt = document("a", "receipt", captured = "2026-11-10T09:00:00Z", size = 300)
    private val contract = document("b", "Contract", captured = "2026-11-12T09:00:00Z", size = 1_200)
    private val letter = document("c", "Écrit", captured = "2026-11-11T09:00:00Z", size = null)
    private val library = listOf(receipt, contract, letter)

    @Test
    fun `newest and oldest order by capture time`() {
        assertEquals(listOf("b", "c", "a"), ids(sortDocuments(library, DocumentSort.NEWEST)))
        assertEquals(listOf("a", "c", "b"), ids(sortDocuments(library, DocumentSort.OLDEST)))
    }

    @Test
    fun `name order ignores case and accents in the phone's language`() {
        // "Contract" < "Écrit" < "receipt": the accent and the lower-case r do not push a title to the end.
        assertEquals(listOf("b", "c", "a"), ids(sortDocuments(library, DocumentSort.NAME, Locale.ENGLISH)))
    }

    @Test
    fun `size order puts the largest first and unknown sizes last`() {
        assertEquals(listOf("b", "a", "c"), ids(sortDocuments(library, DocumentSort.SIZE)))
    }

    @Test
    fun `ties keep a stable order, newest first`() {
        val older = document("x", "Same", captured = "2026-01-01T00:00:00Z", size = 10)
        val newer = document("y", "same", captured = "2026-02-01T00:00:00Z", size = 10)
        assertEquals(listOf("y", "x"), ids(sortDocuments(listOf(older, newer), DocumentSort.NAME, Locale.ENGLISH)))
        assertEquals(listOf("y", "x"), ids(sortDocuments(listOf(older, newer), DocumentSort.SIZE)))
    }

    @Test
    fun `a long press opens the selection and later taps toggle`() {
        val opened = toggleSelection(emptySet(), "a")
        assertEquals(setOf("a"), opened)
        val two = toggleSelection(opened, "b")
        assertEquals(setOf("a", "b"), two)
        // Un-picking the last one closes the selection (the set is empty again).
        assertTrue(toggleSelection(toggleSelection(two, "a"), "b").isEmpty())
    }

    @Test
    fun `select all picks every document on screen, and a second time none`() {
        val all = toggleSelectAll(setOf("a"), library)
        assertEquals(setOf("a", "b", "c"), all)
        assertTrue(toggleSelectAll(all, library).isEmpty())
        // Nothing listed: nothing to pick.
        assertTrue(toggleSelectAll(emptySet(), emptyList()).isEmpty())
    }

    @Test
    fun `documents that disappear leave the selection`() {
        assertEquals(setOf("b"), pruneSelection(setOf("b", "gone"), library))
        assertTrue(pruneSelection(setOf("gone"), library).isEmpty())
    }

    @Test
    fun `shared documents keep the order of the list`() {
        val shown = sortDocuments(library, DocumentSort.OLDEST)
        assertEquals(listOf("a", "b"), selectedInDisplayOrder(setOf("b", "a"), shown))
    }

    @Test
    fun `deleting says the backup copy goes too only when there is one`() {
        val localOnly = document("l", "Local", state = SyncState.LOCAL_ONLY)
        val synced = document("s", "Synced", state = SyncState.SYNCED)
        assertFalse(deletionReachesBackup(listOf(localOnly), signedIn = true))
        assertTrue(deletionReachesBackup(listOf(localOnly, synced), signedIn = true))
        assertFalse(deletionReachesBackup(listOf(synced), signedIn = false))
    }

    private fun ids(documents: List<Document>) = documents.map { it.id }

    private fun document(
        id: String,
        title: String,
        captured: String = "2026-11-15T10:00:00Z",
        size: Long? = null,
        state: SyncState = SyncState.LOCAL_ONLY,
    ) = Document(
        id = id,
        title = title,
        tags = emptyList(),
        pageCount = 1,
        sizeBytes = size,
        capturedAt = Instant.parse(captured),
        updatedAt = Instant.parse(captured),
        syncState = state,
        hasUnsyncedChanges = false,
        syncError = null,
        storedOnDevice = true,
    )
}
