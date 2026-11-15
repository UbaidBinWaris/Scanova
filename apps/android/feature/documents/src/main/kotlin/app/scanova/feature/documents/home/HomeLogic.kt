package app.scanova.feature.documents.home

import app.scanova.core.data.model.Document

/** How many documents "Recent documents" shows; "See all" leads to the rest. */
internal const val RECENT_COUNT = 6

/** The newest documents first, at most [limit] of them. */
internal fun recentDocuments(documents: List<Document>, limit: Int = RECENT_COUNT): List<Document> =
    documents.sortedWith(compareByDescending<Document> { it.capturedAt }.thenBy { it.id }).take(limit)

/** What a search returned, and for which (trimmed) words. */
internal data class SearchAnswer(
    val query: String,
    val documents: List<Document>,
    /** The search could not run (the local database could not be read). */
    val failed: Boolean = false,
)

/** What Home shows below the search field. */
internal sealed interface SearchState {
    /** Nothing typed: Home shows its quick actions and recent documents. */
    data object Inactive : SearchState

    /** Words typed, no answer yet. */
    data object Searching : SearchState

    data object Failed : SearchState

    /**
     * Matching documents (empty: nothing found). [upToDate] is false while the
     * answer still belongs to the previous words, so the list does not flash
     * empty on every keystroke.
     */
    data class Results(val documents: List<Document>, val upToDate: Boolean) : SearchState
}

internal fun searchState(query: String, answer: SearchAnswer?): SearchState {
    val wanted = query.trim()
    return when {
        wanted.isEmpty() -> SearchState.Inactive
        answer == null -> SearchState.Searching
        answer.failed -> if (answer.query == wanted) SearchState.Failed else SearchState.Searching
        else -> SearchState.Results(answer.documents, upToDate = answer.query == wanted)
    }
}
