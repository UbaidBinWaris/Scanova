package app.scanova.feature.documents.home

import app.scanova.core.data.model.Document
import app.scanova.core.data.model.SyncState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLogicTest {

    private val invoice = document("i", "Invoice")
    private val lease = document("l", "Lease")

    @Test
    fun `nothing typed shows the dashboard`() {
        assertEquals(SearchState.Inactive, searchState("", null))
        // Spaces alone are not a search, even with an old answer around.
        assertEquals(SearchState.Inactive, searchState("   ", SearchAnswer("lease", listOf(lease))))
    }

    @Test
    fun `typed words wait for their first answer`() {
        assertEquals(SearchState.Searching, searchState("inv", null))
    }

    @Test
    fun `an answer for the typed words is up to date`() {
        val state = searchState(" invoice ", SearchAnswer("invoice", listOf(invoice)))
        assertEquals(SearchState.Results(listOf(invoice), upToDate = true), state)
    }

    @Test
    fun `the previous answer stays on screen while the next one is on its way`() {
        val state = searchState("invoice 2026", SearchAnswer("invoice", listOf(invoice)))
        assertTrue(state is SearchState.Results)
        state as SearchState.Results
        assertFalse(state.upToDate)
        assertEquals(listOf(invoice), state.documents)
    }

    @Test
    fun `an empty up-to-date answer means nothing was found`() {
        assertEquals(SearchState.Results(emptyList(), upToDate = true), searchState("zzz", SearchAnswer("zzz", emptyList())))
    }

    @Test
    fun `a failed search is reported only for the words it was for`() {
        val failed = SearchAnswer("lease", emptyList(), failed = true)
        assertEquals(SearchState.Failed, searchState("lease", failed))
        assertEquals(SearchState.Searching, searchState("lease 2", failed))
    }

    @Test
    fun `recent documents are the newest few`() {
        val documents = (1..9).map { day -> document("d$day", "Doc $day", captured = "2026-11-%02dT08:00:00Z".format(day)) }
        val recent = recentDocuments(documents.shuffled(), limit = 6)
        assertEquals(listOf("d9", "d8", "d7", "d6", "d5", "d4"), recent.map { it.id })
        assertEquals(2, recentDocuments(documents.take(2)).size)
    }

    private fun document(id: String, title: String, captured: String = "2026-11-15T10:00:00Z") = Document(
        id = id,
        title = title,
        tags = emptyList(),
        pageCount = 2,
        sizeBytes = 1_000,
        capturedAt = Instant.parse(captured),
        updatedAt = Instant.parse(captured),
        syncState = SyncState.SYNCED,
        hasUnsyncedChanges = false,
        syncError = null,
        storedOnDevice = true,
    )
}
