package app.scanova.feature.scan.cleanup

import app.scanova.core.data.model.PageEdit
import app.scanova.feature.scan.filter.ServerCleanup
import app.scanova.feature.scan.render.GeometryKey
import app.scanova.feature.scan.render.geometryKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pages cleaned by the server (Server clean-up), kept in memory only — never
 * written to disk — as the JPEG the server sent back, by page and geometry: a
 * new crop, turn or flattening needs a new clean-up, while a new strength,
 * brightness or contrast reuses the one kept.
 *
 * At most [MAX_ENTRIES] pages (a few MB each), the least recently used
 * dropped first. Thread-safe: the scan's processor writes from the
 * background, the screens read on the main thread.
 */
@Singleton
internal class CleanedPages @Inject constructor() {

    private data class Key(val pageId: String, val geometry: GeometryKey)

    private val entries = object : LinkedHashMap<Key, ByteArray>(MAX_ENTRIES, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ByteArray>): Boolean = size > MAX_ENTRIES
    }

    private val mutableVersion = MutableStateFlow(0L)

    /** Changes whenever a cleaned page is kept or dropped, so views showing a stand-in can look again. */
    val version: StateFlow<Long> = mutableVersion.asStateFlow()

    /** The cleaned page [pageId] for [geometry], as JPEG; null when none is kept. Counts as a use. */
    fun get(pageId: String, geometry: GeometryKey): ByteArray? = synchronized(entries) { entries[Key(pageId, geometry)] }

    /** A cleaned page is kept for [pageId] and [geometry] (without counting as a use). */
    fun contains(pageId: String, geometry: GeometryKey): Boolean = synchronized(entries) { entries.containsKey(Key(pageId, geometry)) }

    /** Keeps [jpeg], page [pageId] cleaned for [geometry], in place of any earlier one. */
    fun put(pageId: String, geometry: GeometryKey, jpeg: ByteArray) {
        synchronized(entries) { entries[Key(pageId, geometry)] = jpeg }
        mutableVersion.update { it + 1 }
    }

    /** Drops everything kept for [pageId] (the page was deleted, or its scan discarded). */
    fun forget(pageId: String) {
        val removed = synchronized(entries) { entries.keys.removeAll { it.pageId == pageId } }
        if (removed) mutableVersion.update { it + 1 }
    }

    /** How many cleaned pages are kept. */
    val size: Int get() = synchronized(entries) { entries.size }

    /**
     * Page [pageId] must be cleaned by the server before [edit] is rendered:
     * its look is Server clean-up and nothing is kept for its geometry.
     */
    fun needsCleaning(pageId: String, edit: PageEdit): Boolean =
        ServerCleanup.needsCleaning(edit.filter, hasCleaned = contains(pageId, edit.geometryKey))

    companion object {
        /** Cleaned pages kept at most: a scan or a document's worth, about 1–2 MB each. */
        const val MAX_ENTRIES = 24
        private const val LOAD_FACTOR = 0.75f
    }
}
