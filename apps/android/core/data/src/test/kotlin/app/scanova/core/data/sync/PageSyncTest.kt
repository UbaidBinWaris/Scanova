package app.scanova.core.data.sync

import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.common.util.sha256
import app.scanova.core.common.util.toHex
import app.scanova.core.data.model.SyncState
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A PDF stand-in: "FAKEPDF:<pages>". [FakePdfImages] turns it into that many page images. */
private fun fakePdf(pages: Int, salt: String = ""): ByteArray = "FAKEPDF:$pages:$salt".toByteArray()

private object FakePdfImages : PdfPageImages {
    override fun pages(open: () -> InputStream, onPage: (Int, ByteArray) -> Unit): Int? {
        val text = open().use { it.readBytes() }.toString(Charsets.ISO_8859_1)
        if (!text.startsWith("FAKEPDF:")) return null
        val count = text.removePrefix("FAKEPDF:").substringBefore(':').toInt()
        repeat(count) { onPage(it, tinyJpeg(1000 + it, 1400, marker = it)) }
        return count
    }
}

class PageSyncTest {

    private val api = FakeSyncApi()
    private var nextId = 0

    private fun engine(
        store: FakeSyncStore,
        files: FakeSyncFiles,
        limits: RestoreLimits = RestoreLimits(),
        sources: List<PdfPageImages> = listOf(FakePdfImages),
    ) = SyncEngine(
        store = store,
        api = api,
        files = files,
        pages = PagePusher(store, api, files, io = Dispatchers.Unconfined),
        restorer = PageRestorer(
            store,
            api,
            files,
            sources = sources,
            limits = limits,
            elapsedMillis = { 0L },
            io = Dispatchers.Unconfined,
            newId = { "generated-${nextId++}" },
        ),
    )

    private fun photo(name: String) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + name.toByteArray()

    /** A document scanned here: PDF and two pages with originals, nothing sent yet. */
    private fun scanned(id: String = "a"): Pair<FakeSyncStore, FakeSyncFiles> {
        val store = FakeSyncStore(localDocument(id, pagesDirty = true))
        val files = FakeSyncFiles("pdf/$id.pdf")
        for ((index, page) in listOf("$id-p1", "$id-p2").withIndex()) {
            val bytes = photo(page)
            files.put("originals/$page.jpg", bytes)
            store.addPages(
                localPage(page, id, index, originalSha256 = sha256(bytes).toHex(), originalSizeBytes = bytes.size.toLong()),
            )
        }
        return store to files
    }

    // ── Backup ──────────────────────────────────────────────────────────

    @Test
    fun `after its PDF, a document's page list and originals are backed up`() = runTest {
        val (store, files) = scanned()

        val report = engine(store, files).run()

        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertEquals(
            listOf("create a", "upload a", "put pages a", "put original a/a-p1", "put original a/a-p2", "list cursor=null since=null"),
            api.calls,
        )
        val document = store.documents.getValue("a")
        assertEquals(SyncState.SYNCED, document.state)
        assertFalse(document.pagesDirty)
        assertTrue(store.pagesOf("a").all { it.originalUploaded })
        // The newest server timestamp is remembered, so the next pull does not mistake it for a change elsewhere.
        assertEquals(api.serverPages.getValue("a").pagesUpdatedAtIso, document.remotePagesUpdatedAt)
        assertEquals("""{"v":1,"filter":"enhanced"}""", api.serverPages.getValue("a").pages[0].editJson)
        assertArrayEquals(photo("a-p2"), api.serverOriginals.getValue("a/a-p2"))

        // Nothing is sent twice.
        api.calls.clear()
        engine(store, files).run()
        assertEquals(listOf("list cursor=null since=null"), api.calls)
    }

    @Test
    fun `an interrupted page backup resumes without sending an original twice`() = runTest {
        val (store, files) = scanned()
        api.uploadOriginalResult = { _, page -> if (page == "a-p2") AppResult.Failure(AppError.NoConnection) else null }

        val first = engine(store, files).run()

        assertEquals(SyncReport(SyncOutcome.RETRY_LATER, AppError.NoConnection), first)
        // The document itself is done; only its page backup waits.
        assertEquals(SyncState.SYNCED, store.state("a"))
        assertTrue(store.documents.getValue("a").pagesDirty)
        assertTrue(store.pagesOf("a")[0].originalUploaded)
        assertFalse(store.pagesOf("a")[1].originalUploaded)

        api.calls.clear()
        api.uploadOriginalResult = { _, _ -> null }
        val second = engine(store, files).run()

        assertEquals(SyncOutcome.COMPLETE, second.outcome)
        assertEquals(listOf("put pages a", "put original a/a-p2", "list cursor=null since=null"), api.calls)
        assertFalse(store.documents.getValue("a").pagesDirty)
    }

    @Test
    fun `a server without page backup never fails or blocks a document`() = runTest {
        val (store, files) = scanned("a")
        val (other, otherFiles) = scanned("b")
        store.documents.putAll(other.documents)
        store.pages.putAll(other.pages)
        otherFiles.contents.forEach { (name, bytes) -> files.put(name, bytes) }
        api.pagesSupported = false

        val report = engine(store, files).run()

        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        for (id in listOf("a", "b")) {
            val document = store.documents.getValue(id)
            assertEquals(SyncState.SYNCED, document.state)
            assertNull(document.lastErrorCode)
            // Kept for when the server is updated.
            assertTrue(document.pagesDirty)
        }
        // One refused page list is enough to know; no original is sent.
        assertEquals(1, api.calls.count { it.startsWith("put pages") })
        assertFalse(api.calls.any { it.startsWith("put original") })
        assertTrue(api.calls.last().startsWith("list"))

        // After the server update, the next run backs everything up.
        api.pagesSupported = true
        api.calls.clear()
        engine(store, files).run()
        assertFalse(store.documents.getValue("a").pagesDirty)
        assertFalse(store.documents.getValue("b").pagesDirty)
        assertEquals(4, api.calls.count { it.startsWith("put original") })
    }

    @Test
    fun `an answer that is not from the API, or a 405, counts as a server without page backup`() = runTest {
        for (error in listOf(AppError.UnexpectedResponse, AppError.Api(ApiErrorCode.UNKNOWN, 405))) {
            val (store, files) = scanned()
            api.replacePagesResult = { AppResult.Failure(error) }

            val report = engine(store, files).run()

            assertEquals(SyncOutcome.COMPLETE, report.outcome)
            assertEquals(SyncState.SYNCED, store.state("a"))
            assertTrue(store.documents.getValue("a").pagesDirty)
        }
    }

    @Test
    fun `a full account stops originals for this run but not page lists`() = runTest {
        val (store, files) = scanned("a")
        val (other, otherFiles) = scanned("b")
        store.documents.putAll(other.documents)
        store.pages.putAll(other.pages)
        otherFiles.contents.forEach { (name, bytes) -> files.put(name, bytes) }
        api.uploadOriginalResult = { _, _ -> api(ApiErrorCode.QUOTA_EXCEEDED, 413) }

        val report = engine(store, files).run()

        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertEquals(2, api.calls.count { it.startsWith("put pages") })
        assertEquals(1, api.calls.count { it.startsWith("put original") })
        assertTrue(store.documents.getValue("a").pagesDirty)
        assertTrue(store.documents.getValue("b").pagesDirty)
        assertEquals(SyncState.SYNCED, store.state("a"))
    }

    @Test
    fun `a missing or wrong hash of an original is computed from the file`() = runTest {
        val (store, files) = scanned()
        store.pages["a-p1"] = store.pages.getValue("a-p1").copy(originalSha256 = null, originalSizeBytes = null)
        store.pages["a-p2"] = store.pages.getValue("a-p2").copy(originalSha256 = "00".repeat(32))

        engine(store, files).run()

        // p1 was hashed before sending; p2's wrong hash was refused and forgotten.
        assertEquals(sha256(photo("a-p1")).toHex(), api.sentOriginals.getValue("a-p1").first)
        assertTrue(store.pagesOf("a")[0].originalUploaded)
        assertNull(store.pagesOf("a")[1].originalSha256)
        assertTrue(store.documents.getValue("a").pagesDirty)

        engine(store, files).run()

        assertTrue(store.pagesOf("a")[1].originalUploaded)
        assertFalse(store.documents.getValue("a").pagesDirty)
    }

    @Test
    fun `an edit to a synced document sends only its page list and new originals`() = runTest {
        val store = FakeSyncStore(
            localDocument("a", state = SyncState.SYNCED, remoteCreated = true, fileUploaded = true, pagesDirty = true),
        )
        val files = FakeSyncFiles("pdf/a.pdf", "originals/new.jpg")
        store.addPages(
            localPage("old", "a", 0, originalUploaded = true, originalSha256 = "11".repeat(32), originalSizeBytes = 9),
            localPage("new", "a", 1, originalFile = "originals/new.jpg"),
        )

        engine(store, files).run()

        assertEquals(listOf("put pages a", "put original a/new", "list cursor=null since=null"), api.calls)
        assertFalse(store.documents.getValue("a").pagesDirty)
    }

    // ── Restore ─────────────────────────────────────────────────────────

    /** A document another device backed up: PDF, page list with recipes, and originals. */
    private fun backedUp(id: String, pages: Int = 2, withOriginals: Boolean = true): RemoteDocument {
        val pdf = fakePdf(pages, id)
        api.serverPdfs[id] = pdf
        api.serverPages[id] = RemotePageList(
            "2026-11-15T08:00:00.000Z",
            (0 until pages).map { index ->
                val original = photo("$id-original-$index")
                if (withOriginals) api.serverOriginals["$id/$id-p$index"] = original
                RemotePageInfo(
                    id = "$id-p$index",
                    position = index,
                    width = 1000 + index,
                    height = 1400,
                    editJson = """{"v":1,"rotation":${index * 90}}""",
                    originalSha256 = if (withOriginals) sha256(original).toHex() else null,
                    originalSizeBytes = if (withOriginals) original.size.toLong() else null,
                    updatedAtIso = "2026-11-15T08:00:00.000Z",
                )
            },
        )
        return remoteDocument(id, contentSha256 = sha256(pdf).toHex(), pagesUpdatedAt = "2026-11-15T08:00:00.000Z")
    }

    @Test
    fun `a document from the server becomes complete on this phone`() = runTest {
        val store = FakeSyncStore()
        val files = FakeSyncFiles()
        api.listPages = mutableListOf(AppResult.Success(RemotePage(listOf(backedUp("r")), null)))

        val report = engine(store, files).run()

        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertFalse(report.restorePending)
        assertEquals(
            listOf(
                "list cursor=null since=null",
                "get pages r",
                "get pdf r",
                "get original r/r-p0",
                "get original r/r-p1",
            ),
            api.calls,
        )
        val document = store.documents.getValue("r")
        assertFalse(document.pagesStale)
        assertEquals("2026-11-15T08:00:00.000Z", document.remotePagesUpdatedAt)
        // No searchable copy on the server: the downloaded PDF is kept as the document's file.
        assertArrayEquals(fakePdf(2, "r"), files.contents.getValue(document.pdfFile!!))
        val pages = store.pagesOf("r")
        assertEquals(listOf("r-p0", "r-p1"), pages.map { it.id })
        assertEquals(listOf(1000, 1001), pages.map { it.width })
        assertEquals("""{"v":1,"rotation":90}""", pages[1].editJson)
        // The current images are exactly the PDF's page images.
        assertArrayEquals(tinyJpeg(1001, 1400, marker = 1), files.contents.getValue(pages[1].imageFile))
        for ((index, page) in pages.withIndex()) {
            assertTrue(page.originalUploaded)
            assertArrayEquals(photo("r-original-$index"), files.contents.getValue(page.originalFile!!))
        }
    }

    @Test
    fun `with a server without page backup, pages still come from the PDF`() = runTest {
        val store = FakeSyncStore()
        val files = FakeSyncFiles()
        val remote = backedUp("r", pages = 3).copy(pagesUpdatedAtIso = null)
        api.pagesSupported = false
        api.listPages = mutableListOf(AppResult.Success(RemotePage(listOf(remote), null)))

        val report = engine(store, files).run()

        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        val pages = store.pagesOf("r")
        assertEquals(3, pages.size)
        assertTrue(pages.all { it.editJson == null && it.originalFile == null && !it.originalUploaded })
        assertTrue(pages.all { it.id.startsWith("generated-") })
        assertFalse(store.documents.getValue("r").pagesStale)
        assertFalse(api.calls.any { it.startsWith("get original") })
    }

    @Test
    fun `a searchable copy on the server keeps the downloaded PDF from becoming the document's file`() = runTest {
        val store = FakeSyncStore()
        val files = FakeSyncFiles()
        val remote = backedUp("r").copy(ocrStatus = "DONE", hasSearchablePdf = true)
        api.listPages = mutableListOf(AppResult.Success(RemotePage(listOf(remote), null)))

        engine(store, files).run()

        assertNull(store.documents.getValue("r").pdfFile)
        assertEquals(2, store.pagesOf("r").size)
        // Only page images and originals are left in the store.
        assertTrue(files.contents.keys.none { it.startsWith("pdf/") })
    }

    @Test
    fun `restore is bounded per run and continues in the next one`() = runTest {
        val store = FakeSyncStore()
        val files = FakeSyncFiles()
        val documents = listOf(backedUp("r1", withOriginals = false), backedUp("r2", withOriginals = false))
        api.listPages = mutableListOf(AppResult.Success(RemotePage(documents, null)))

        // Any download spends the budget of one run.
        val first = engine(store, files, limits = RestoreLimits(maxBytes = 1)).run()

        assertEquals(SyncOutcome.COMPLETE, first.outcome)
        assertTrue(first.restorePending)
        assertEquals(1, store.documents.values.count { !it.pagesStale })

        api.listPages = mutableListOf(AppResult.Success(RemotePage(emptyList(), null)))
        val second = engine(store, files, limits = RestoreLimits(maxBytes = 1)).run()

        assertTrue(second.restorePending || store.documents.values.none { it.pagesStale })
        engine(store, files).run()
        assertTrue(store.documents.values.none { it.pagesStale })
        assertEquals(2, store.pagesOf("r1").size)
        assertEquals(2, store.pagesOf("r2").size)
    }

    @Test
    fun `restore failures never fail the sync and are retried later`() = runTest {
        val store = FakeSyncStore()
        val files = FakeSyncFiles()
        api.listPages = mutableListOf(AppResult.Success(RemotePage(listOf(backedUp("r")), null)))
        api.downloadPdfResult = { AppResult.Failure(AppError.NoConnection) }

        val report = engine(store, files).run()

        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertTrue(store.documents.getValue("r").pagesStale)
        assertTrue(store.pagesOf("r").isEmpty())
        // Nothing half-written is left behind.
        assertTrue(files.contents.isEmpty())

        api.downloadPdfResult = { null }
        api.listPages = mutableListOf(AppResult.Success(RemotePage(emptyList(), null)))
        engine(store, files).run()

        assertFalse(store.documents.getValue("r").pagesStale)
        assertEquals(2, store.pagesOf("r").size)
    }

    @Test
    fun `originals are downloaded one by one, and each is kept as soon as it is stored`() = runTest {
        val store = FakeSyncStore()
        val files = FakeSyncFiles()
        api.listPages = mutableListOf(AppResult.Success(RemotePage(listOf(backedUp("r")), null)))
        api.downloadOriginalResult = { _, page -> if (page == "r-p1") AppResult.Failure(AppError.NoConnection) else null }

        engine(store, files).run()

        val pages = store.pagesOf("r")
        assertTrue(pages[0].originalFile != null)
        assertNull(pages[1].originalFile)
        assertTrue(pages[1].originalUploaded)

        api.calls.clear()
        api.downloadOriginalResult = { _, _ -> null }
        api.listPages = mutableListOf(AppResult.Success(RemotePage(emptyList(), null)))
        engine(store, files).run()

        assertEquals(listOf("list cursor=null since=2026-11-10T09:05:00.000Z", "get original r/r-p1"), api.calls)
        assertTrue(store.pagesOf("r")[1].originalFile != null)
    }

    @Test
    fun `a PDF no source can read leaves the document as it is and is not tried again`() = runTest {
        val store = FakeSyncStore()
        val files = FakeSyncFiles()
        val remote = backedUp("r")
        val unreadable = "%PDF-1.7 something else".toByteArray()
        api.serverPdfs["r"] = unreadable
        api.listPages = mutableListOf(AppResult.Success(RemotePage(listOf(remote.copy(contentSha256 = sha256(unreadable).toHex())), null)))

        val report = engine(store, files).run()

        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertFalse(store.documents.getValue("r").pagesStale)
        assertTrue(store.pagesOf("r").isEmpty())
        assertTrue(files.contents.isEmpty())
    }

    @Test
    fun `pages changed on another device replace the pages here, keeping originals already here`() = runTest {
        // This phone has the document with two pages and both originals.
        val oldPdf = fakePdf(2, "old")
        val store = FakeSyncStore(
            localDocument(
                "a",
                state = SyncState.SYNCED,
                remoteCreated = true,
                fileUploaded = true,
                contentSha256 = sha256(oldPdf).toHex(),
                remotePagesUpdatedAt = "2026-11-14T00:00:00.000Z",
            ),
        )
        val files = FakeSyncFiles()
        files.put("pdf/a.pdf", oldPdf)
        val keptOriginal = photo("a-original-0")
        files.put("originals/a-p0.jpg", keptOriginal)
        files.put("originals/a-p9.jpg", photo("dropped"))
        store.addPages(
            localPage("a-p0", "a", 0, originalSha256 = sha256(keptOriginal).toHex(), originalUploaded = true),
            localPage("a-p9", "a", 1, originalFile = "originals/a-p9.jpg", originalSha256 = "99".repeat(32), originalUploaded = true),
        )
        files.put("pages/a-p0.jpg", tinyJpeg(1, 1))
        files.put("pages/a-p9.jpg", tinyJpeg(1, 1))
        // Another device removed the second page and added two: a new PDF and page list.
        val remote = backedUp("a", pages = 3)
        api.listPages = mutableListOf(AppResult.Success(RemotePage(listOf(remote), null)))

        engine(store, files).run()

        val document = store.documents.getValue("a")
        assertEquals(remote.contentSha256, document.contentSha256)
        assertFalse(document.pagesStale)
        val pages = store.pagesOf("a")
        assertEquals(listOf("a-p0", "a-p1", "a-p2"), pages.map { it.id })
        // The original already here was kept, the others were fetched.
        assertEquals("originals/a-p0.jpg", pages[0].originalFile)
        assertFalse("get original a/a-p0" in api.calls)
        assertTrue("get original a/a-p1" in api.calls)
        // What the document no longer uses is gone: old images and the removed page's original.
        // (The outdated PDF is deleted by the Room store when the pull sees the new one.)
        for (name in listOf("pages/a-p0.jpg", "pages/a-p9.jpg", "originals/a-p9.jpg")) {
            assertFalse(name, files.exists(name))
        }
    }

    @Test
    fun `local page work wins over the server until it is sent`() = runTest {
        val store = FakeSyncStore(
            localDocument("a", state = SyncState.SYNCED, remoteCreated = true, fileUploaded = true, pagesDirty = true),
        )
        val files = FakeSyncFiles("pdf/a.pdf")
        store.addPages(localPage("a-p0", "a", 0, originalFile = null))
        api.pagesSupported = false
        api.listPages = mutableListOf(
            AppResult.Success(RemotePage(listOf(remoteDocument("a", pagesUpdatedAt = "2026-11-16T00:00:00.000Z")), null)),
        )

        engine(store, files).run()

        assertFalse(store.documents.getValue("a").pagesStale)
        assertFalse(api.calls.any { it.startsWith("get pages") || it.startsWith("get pdf") })
    }

    @Test
    fun `a pull to refresh leaves the restore to the background`() = runTest {
        val store = FakeSyncStore()
        val files = FakeSyncFiles()
        api.listPages = mutableListOf(AppResult.Success(RemotePage(listOf(backedUp("r")), null)))

        val report = engine(store, files).run(restorePages = false)

        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertTrue(report.restorePending)
        assertEquals(listOf("list cursor=null since=null"), api.calls)
        assertTrue(store.documents.getValue("r").pagesStale)
    }

    @Test
    fun `documents restored before page restore existed are queued once`() = runTest {
        val pdf = fakePdf(1, "legacy")
        api.serverPdfs["legacy"] = pdf
        api.pagesSupported = false
        val store = FakeSyncStore(
            localDocument(
                "legacy",
                state = SyncState.SYNCED,
                remoteCreated = true,
                fileUploaded = true,
                pdfFile = null,
                contentSha256 = sha256(pdf).toHex(),
            ),
        )
        val files = FakeSyncFiles()

        engine(store, files).run()

        assertTrue(store.backfilled)
        assertEquals(1, store.pagesOf("legacy").size)
    }
}
