package app.scanova.core.data.indexing

import app.scanova.core.common.util.TimeSource
import app.scanova.core.data.documents.decodeTags
import app.scanova.core.data.documents.encodeTags
import app.scanova.core.data.sync.SyncScheduler
import app.scanova.core.database.dao.TextIndexDao
import app.scanova.core.database.entity.DocumentEntity
import app.scanova.core.database.entity.LocalSyncState
import app.scanova.core.database.entity.PageEntity
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextIndexerTest {

    private val receiptText = """
        IMTIAZ STORE
        SALES RECEIPT
        Date: 16/11/2026
        Milk 2 560.00
        Bread 1 180.00
        Total 740.00
        Cash 1,000.00
        Change 260.00
        Thank you for shopping with us!
    """.trimIndent()

    private val dao = FakeTextIndexDao()
    private val sync = CountingSyncScheduler()
    private val now = Instant.parse("2026-11-16T12:00:00Z")

    /** What each image file reads as; anything else is a page without text. */
    private val texts = mutableMapOf<String, String>()
    private val reads = mutableListOf<String>()
    private var recognizerWorks = true
    private val unreadable = mutableSetOf<String>()

    private fun indexer(maxPages: Int = 40) = TextIndexer(
        dao = dao,
        images = { name ->
            when {
                name in unreadable -> PageImage.Unreadable
                name.startsWith("missing") -> PageImage.Missing
                else -> PageImage.Found(name.toByteArray())
            }
        },
        reader = { bytes ->
            val name = String(bytes)
            reads += name
            if (!recognizerWorks) PageText.RecognizerUnavailable else PageText.Read(texts[name].orEmpty())
        },
        syncScheduler = sync,
        clock = TimeSource { now },
        maxPages = maxPages,
    )

    @Test
    fun `an imported document gets its text, its type and a title`() = runTest {
        dao.add(document("d1", title = "Imported 16 Nov 2026"), page("p1", "d1", 0))
        texts["pages/p1.jpg"] = receiptText

        val report = indexer().run()

        assertEquals(1, report.pagesRead)
        assertEquals(1, report.documentsChanged)
        assertFalse(report.moreWork)
        val page = dao.pages.getValue("p1")
        assertEquals(receiptText, page.ocrText)
        assertEquals("pages/p1.jpg", page.textCheckedImage)
        val document = dao.documents.getValue("d1")
        assertEquals(receiptText, document.ocrText)
        assertEquals("Receipt – Imtiaz Store – 16 Nov 2026", document.title)
        assertEquals(listOf("Receipt"), decodeTags(document.tags))
        assertEquals(IndexRules.ANALYSIS_VERSION, document.analysisVersion)
        // Not on the server yet: the create request carries the new title and tag.
        assertFalse(document.metadataDirty)
        assertEquals(0, sync.requests)
    }

    @Test
    fun `a backed-up document's new title and tag are sent as a metadata edit`() = runTest {
        dao.add(document("d1", title = "Scan 16 Nov 2026, 10:05", remoteCreated = true, state = LocalSyncState.SYNCED), page("p1", "d1", 0))
        texts["pages/p1.jpg"] = receiptText

        indexer().run()

        val document = dao.documents.getValue("d1")
        assertTrue(document.metadataDirty)
        assertEquals(now.toEpochMilli(), document.updatedAt)
        assertEquals(1, sync.requests)
    }

    @Test
    fun `a title someone typed and their tags are kept, the type is added`() = runTest {
        dao.add(document("d1", title = "Groceries for Eid", tags = listOf("home", "2026"), ocrText = receiptText), page("p1", "d1", 0, receiptText))

        indexer().run()

        val document = dao.documents.getValue("d1")
        assertEquals("Groceries for Eid", document.title)
        assertEquals(listOf("home", "2026", "Receipt"), decodeTags(document.tags))
        assertTrue(reads.isEmpty())
    }

    @Test
    fun `a type tag someone set is respected, and a removed one never comes back`() = runTest {
        dao.add(document("d1", tags = listOf("invoice"), ocrText = receiptText), page("p1", "d1", 0, receiptText))
        // Analysed before; the user has since removed the type tag.
        dao.add(document("d2", tags = emptyList(), ocrText = receiptText, analysisVersion = IndexRules.ANALYSIS_VERSION), page("p2", "d2", 0, receiptText))

        indexer().run()

        assertEquals(listOf("invoice"), decodeTags(dao.documents.getValue("d1").tags))
        assertEquals(emptyList<String>(), decodeTags(dao.documents.getValue("d2").tags))
    }

    @Test
    fun `text from the server's OCR stays the document's text`() = runTest {
        val serverText = "کرایہ نامہ — server OCR, Urdu included"
        dao.add(document("d1", ocrText = serverText, title = "Rent agreement"), page("p1", "d1", 0), page("p2", "d1", 1))
        texts["pages/p1.jpg"] = "Rent agreement page one"

        indexer().run()
        indexer().run()

        // Not read on the phone (Extract Text keeps showing the server's text), and not asked again.
        assertTrue(reads.isEmpty())
        assertEquals("", dao.pages.getValue("p1").ocrText)
        assertEquals("pages/p1.jpg", dao.pages.getValue("p1").textCheckedImage)
        assertEquals(serverText, dao.documents.getValue("d1").ocrText)
    }

    @Test
    fun `a document's text follows its pages as they are read`() = runTest {
        dao.add(document("d1"), page("p1", "d1", 0), page("p2", "d1", 1))
        texts["pages/p1.jpg"] = "first page"
        texts["pages/p2.jpg"] = "second page"

        indexer().run()

        assertEquals("first page\n\nsecond page", dao.documents.getValue("d1").ocrText)
    }

    @Test
    fun `a page without text is read once`() = runTest {
        dao.add(document("d1", title = "Imported 16 Nov 2026"), page("p1", "d1", 0))

        indexer().run()
        indexer().run()

        assertEquals(listOf("pages/p1.jpg"), reads)
        assertEquals("pages/p1.jpg", dao.pages.getValue("p1").textCheckedImage)
        // Nothing to analyse: the title stays until there is text.
        assertEquals("Imported 16 Nov 2026", dao.documents.getValue("d1").title)
        assertEquals(0, dao.documents.getValue("d1").analysisVersion)
    }

    @Test
    fun `a page edited while it was read keeps the edit, and its new image is read next`() = runTest {
        dao.add(document("d1"), page("p1", "d1", 0))
        texts["pages/p1.jpg"] = "old image text"
        texts["pages/p1-r2.jpg"] = "new image text"
        val editing = TextIndexer(
            dao = dao,
            images = { PageImage.Found(it.toByteArray()) },
            reader = { bytes ->
                val name = String(bytes)
                reads += name
                // The editor commits a new image while this one is being read.
                if (name == "pages/p1.jpg") dao.pages["p1"] = dao.pages.getValue("p1").copy(imageFile = "pages/p1-r2.jpg")
                PageText.Read(texts.getValue(name))
            },
            syncScheduler = sync,
            clock = TimeSource { now },
        )

        editing.run()

        assertEquals(listOf("pages/p1.jpg", "pages/p1-r2.jpg"), reads)
        assertEquals("new image text", dao.pages.getValue("p1").ocrText)
        assertEquals("pages/p1-r2.jpg", dao.pages.getValue("p1").textCheckedImage)
    }

    @Test
    fun `without text recognition nothing is marked and the run is retried`() = runTest {
        dao.add(document("d1"), page("p1", "d1", 0))
        recognizerWorks = false

        val report = indexer().run()

        assertTrue(report.recognizerUnavailable)
        assertNull(dao.pages.getValue("p1").textCheckedImage)
    }

    @Test
    fun `an image that cannot be read now is left for a later run, a missing one is done`() = runTest {
        dao.add(document("d1"), page("p1", "d1", 0), page("p2", "d1", 1, imageFile = "missing/p2.jpg"))
        unreadable += "pages/p1.jpg"

        val report = indexer().run()

        assertTrue(report.moreWork)
        assertNull(dao.pages.getValue("p1").textCheckedImage)
        assertEquals("missing/p2.jpg", dao.pages.getValue("p2").textCheckedImage)
    }

    @Test
    fun `each run is bounded and the next one resumes`() = runTest {
        dao.add(document("d1", title = "Imported 16 Nov 2026"), page("p1", "d1", 0), page("p2", "d1", 1), page("p3", "d1", 2))
        texts["pages/p1.jpg"] = receiptText

        val first = indexer(maxPages = 2).run()

        assertTrue(first.moreWork)
        assertEquals(2, first.pagesRead)
        // A page still waits: the document is not analysed on part of its text.
        assertEquals("Imported 16 Nov 2026", dao.documents.getValue("d1").title)

        val second = indexer(maxPages = 2).run()

        assertFalse(second.moreWork)
        assertEquals(1, second.pagesRead)
        assertEquals("Receipt – Imtiaz Store – 16 Nov 2026", dao.documents.getValue("d1").title)
    }

    @Test
    fun `a split part keeps its pages in the new title`() = runTest {
        dao.add(document("d1", title = "Imported 16 Nov 2026 (pages 1–2)", ocrText = receiptText), page("p1", "d1", 0, receiptText))

        indexer().run()

        assertEquals("Receipt – Imtiaz Store – 16 Nov 2026 (pages 1–2)", dao.documents.getValue("d1").title)
    }

    @Test
    fun `documents being deleted or restored are left alone`() = runTest {
        dao.add(document("d1", state = LocalSyncState.PENDING_DELETE), page("p1", "d1", 0))
        dao.add(document("d2", pagesStale = true), page("p2", "d2", 0))

        indexer().run()

        assertTrue(reads.isEmpty())
    }

    // ── Fakes ───────────────────────────────────────────────────────────

    private fun document(
        id: String,
        title: String = "Title $id",
        tags: List<String> = emptyList(),
        ocrText: String = "",
        remoteCreated: Boolean = false,
        state: LocalSyncState = LocalSyncState.LOCAL_ONLY,
        analysisVersion: Int = 0,
        pagesStale: Boolean = false,
    ) = DocumentEntity(
        id = id,
        title = title,
        tags = encodeTags(tags),
        pageCount = 1,
        sizeBytes = 100,
        contentSha256 = null,
        capturedAt = 0,
        createdAt = 0,
        updatedAt = 0,
        ocrText = ocrText,
        pdfFile = "pdf/$id.pdf",
        syncState = state,
        remoteCreated = remoteCreated,
        fileUploaded = remoteCreated,
        metadataDirty = false,
        ocrStatus = "NONE",
        hasSearchablePdf = false,
        lastErrorCode = null,
        syncAttempts = 0,
        pagesStale = pagesStale,
        analysisVersion = analysisVersion,
    )

    private fun page(id: String, documentId: String, position: Int, ocrText: String = "", imageFile: String = "pages/$id.jpg") =
        PageEntity(id = id, documentId = documentId, position = position, imageFile = imageFile, width = 100, height = 100, ocrText = ocrText)

    private class CountingSyncScheduler : SyncScheduler {
        var requests = 0
        override fun requestSync() {
            requests++
        }
        override fun startPeriodicSync() = Unit
        override fun cancelAll() = Unit
    }

    /** The DAO's transactions run for real on top of in-memory tables. */
    private class FakeTextIndexDao : TextIndexDao() {
        val documents = LinkedHashMap<String, DocumentEntity>()
        val pages = LinkedHashMap<String, PageEntity>()

        fun add(document: DocumentEntity, vararg documentPages: PageEntity) {
            documents[document.id] = document
            documentPages.forEach { pages[it.id] = it }
        }

        private fun PageEntity.needsText() = ocrText.isEmpty() && textCheckedImage != imageFile

        override suspend fun pagesNeedingText(limit: Int): List<PageEntity> = pages.values
            .filter { page ->
                val document = documents[page.documentId]
                page.needsText() && document != null && document.syncState != LocalSyncState.PENDING_DELETE && !document.pagesStale
            }
            .sortedBy { it.position }
            .take(limit)

        override suspend fun documentsToAnalyse(version: Int, limit: Int): List<DocumentEntity> = documents.values
            .filter { document ->
                document.analysisVersion < version && document.ocrText.isNotEmpty() &&
                    document.syncState != LocalSyncState.PENDING_DELETE &&
                    pages.values.none { it.documentId == document.id && it.needsText() }
            }
            .take(limit)

        override suspend fun page(pageId: String): PageEntity? = pages[pageId]

        override suspend fun pages(documentId: String): List<PageEntity> =
            pages.values.filter { it.documentId == documentId }.sortedBy { it.position }

        override suspend fun document(id: String): DocumentEntity? = documents[id]

        override suspend fun updatePage(page: PageEntity) {
            pages[page.id] = page
        }

        override suspend fun updateDocument(document: DocumentEntity) {
            documents[document.id] = document
        }
    }
}
