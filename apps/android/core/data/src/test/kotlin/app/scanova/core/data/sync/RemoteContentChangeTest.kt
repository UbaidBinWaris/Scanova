package app.scanova.core.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteContentChangeTest {

    private val sha = "ab".repeat(32)

    private fun change(
        remote: RemoteDocument,
        localSha: String? = sha,
        pagesDirty: Boolean = false,
        pagesStale: Boolean = false,
        localPagesAt: String? = "2026-11-15T08:00:00.000Z",
    ) = remoteContentChange(localSha, pagesDirty, pagesStale, localPagesAt, remote)

    @Test
    fun `nothing changes when the server has the same PDF and pages`() {
        val same = remoteDocument("a", contentSha256 = sha, pagesUpdatedAt = "2026-11-15T08:00:00.000Z")
        assertEquals(ContentChange(pdfReplaced = false, pagesStale = false, pagesDirty = false), change(same))
        // A server without page backup says nothing about pages.
        assertEquals(
            ContentChange(pdfReplaced = false, pagesStale = false, pagesDirty = false),
            change(same.copy(pagesUpdatedAtIso = null)),
        )
    }

    @Test
    fun `a new PDF elsewhere replaces the local one and wins over unsent page work`() {
        val edited = remoteDocument("a", contentSha256 = "cd".repeat(32))
        assertEquals(ContentChange(pdfReplaced = true, pagesStale = true, pagesDirty = false), change(edited))
        assertEquals(
            ContentChange(pdfReplaced = true, pagesStale = true, pagesDirty = false),
            change(edited, pagesDirty = true),
        )
    }

    @Test
    fun `changed pages elsewhere make the pages stale unless page work here is unsent`() {
        val pagesChanged = remoteDocument("a", contentSha256 = sha, pagesUpdatedAt = "2026-11-16T09:00:00.000Z")
        assertEquals(ContentChange(pdfReplaced = false, pagesStale = true, pagesDirty = false), change(pagesChanged))
        assertEquals(
            ContentChange(pdfReplaced = false, pagesStale = false, pagesDirty = true),
            change(pagesChanged, pagesDirty = true),
        )
        // A restore still pending stays pending.
        assertEquals(
            ContentChange(pdfReplaced = false, pagesStale = true, pagesDirty = false),
            change(remoteDocument("a", contentSha256 = sha), pagesStale = true),
        )
    }
}
