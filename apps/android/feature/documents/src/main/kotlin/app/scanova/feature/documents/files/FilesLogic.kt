package app.scanova.feature.documents.files

import app.scanova.core.data.model.Document
import app.scanova.core.data.model.SyncState
import java.text.Collator
import java.util.Locale

/** The orders the Files tab offers. */
internal enum class DocumentSort {
    /** Scanned most recently first (the library's natural order). */
    NEWEST,
    OLDEST,

    /** A to Z in the phone's language, ignoring case and accents. */
    NAME,

    /** Largest file first; documents whose size is not known yet go last. */
    SIZE,
}

/**
 * [documents] in the chosen [sort]. Ties fall back to newest first, then to the
 * id, so the order never jumps between two refreshes of the same list.
 */
internal fun sortDocuments(
    documents: List<Document>,
    sort: DocumentSort,
    locale: Locale = Locale.getDefault(),
): List<Document> {
    val newestFirst = compareByDescending<Document> { it.capturedAt }.thenBy { it.id }
    val comparator: Comparator<Document> = when (sort) {
        DocumentSort.NEWEST -> newestFirst
        DocumentSort.OLDEST -> compareBy<Document> { it.capturedAt }.thenBy { it.id }
        DocumentSort.NAME -> {
            val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
            Comparator<Document> { a, b -> collator.compare(a.title.trim(), b.title.trim()) }.then(newestFirst)
        }
        DocumentSort.SIZE -> compareByDescending<Document> { it.sizeBytes ?: -1L }.then(newestFirst)
    }
    return documents.sortedWith(comparator)
}

// ── Multi-selection ─────────────────────────────────────────────────────
// A selection is a set of document ids; it is "open" while it is not empty.

/** Picks [id] or puts it back. Long-pressing the first item opens the selection this way. */
internal fun toggleSelection(selected: Set<String>, id: String): Set<String> =
    if (id in selected) selected - id else selected + id

/** Every document on screen, or none when all of them are picked already. */
internal fun toggleSelectAll(selected: Set<String>, visible: List<Document>): Set<String> {
    val all = visible.mapTo(LinkedHashSet()) { it.id }
    return if (all.isNotEmpty() && selected.containsAll(all)) emptySet() else all
}

/** Drops picked documents that are gone (deleted here or on another device). */
internal fun pruneSelection(selected: Set<String>, documents: List<Document>): Set<String> {
    if (selected.isEmpty()) return selected
    val present = documents.mapTo(HashSet()) { it.id }
    return selected.filterTo(LinkedHashSet()) { it in present }
}

/** The picked ids in the order they are shown, so a shared PDF keeps the list's order. */
internal fun selectedInDisplayOrder(selected: Set<String>, documents: List<Document>): List<String> =
    documents.filter { it.id in selected }.map { it.id }

/**
 * Whether deleting the [selected] documents also removes something from the
 * account's backup, so the confirmation can say so. Only documents that
 * reached the account have a copy there.
 */
internal fun deletionReachesBackup(selected: Collection<Document>, signedIn: Boolean): Boolean =
    signedIn && selected.any { it.syncState != SyncState.LOCAL_ONLY }
