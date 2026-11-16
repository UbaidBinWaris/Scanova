package app.scanova.core.data.documents

import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.common.util.Sha256CountingOutputStream
import app.scanova.core.common.util.TimeSource
import app.scanova.core.data.model.PageEdit
import app.scanova.core.data.scan.PdfProducer
import app.scanova.core.data.storage.FileNames
import app.scanova.core.data.storage.copy
import app.scanova.core.data.storage.describe
import app.scanova.core.data.sync.SyncEvent
import app.scanova.core.data.sync.SyncRunner
import app.scanova.core.data.sync.SyncScheduler
import app.scanova.core.data.sync.SyncStateMachine
import app.scanova.core.database.dao.DocumentDao
import app.scanova.core.database.entity.DocumentEntity
import app.scanova.core.database.entity.LocalSyncState
import app.scanova.core.database.entity.PageEntity
import app.scanova.core.security.file.EncryptedFileStore
import java.io.IOException
import java.security.GeneralSecurityException
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withContext

/** A page of a saved document, as an editor sees it. */
data class EditablePage(
    val id: String,
    val width: Int,
    val height: Int,
    val ocrText: String,
    /** The page has its untouched original: edits render from it ([DocumentEditor.loadOriginal]). */
    val hasOriginal: Boolean,
    /** How the current image was made; null when unknown (edit from the current image then). */
    val edit: PageEdit?,
    /** Handle of the current image. Pass it back unchanged in [PageDraft.image] when the page did not change. */
    val image: String,
)

/** A page as the editor wants to keep it. */
data class PageDraft(
    val id: String,
    /** [EditablePage.image] when the page is unchanged, or a handle from [DocumentEditor.storeRendered]. */
    val image: String,
    val width: Int,
    val height: Int,
    val ocrText: String,
    val edit: PageEdit?,
)

/**
 * Changes saved documents without ever losing quality: a page's original is
 * never modified, only its current image is re-rendered from it.
 *
 * An edit stages new images first ([storeRendered], [storeOriginal],
 * [duplicatePage]); the document keeps using its old ones until [commit]
 * swaps everything at once. [discard] drops what an abandoned edit staged.
 *
 * Every commit rebuilds the PDF and queues the document for backup again.
 */
interface DocumentEditor {
    fun newPageId(): String

    /** The pages of document [documentId], in order. */
    suspend fun pages(documentId: String): AppResult<List<EditablePage>>

    /** The untouched original of page [pageId]. Fails when the page has none. */
    suspend fun loadOriginal(pageId: String): AppResult<ByteArray>

    /** An image by handle ([EditablePage.image] or one returned by [storeRendered]). */
    suspend fun loadImage(image: String): AppResult<ByteArray>

    /** Stores a newly rendered current image for page [pageId]. Returns its handle. */
    suspend fun storeRendered(pageId: String, jpeg: ByteArray): AppResult<String>

    /** Stores the original of a page added while editing (an id from [newPageId]). */
    suspend fun storeOriginal(pageId: String, jpeg: ByteArray): AppResult<Unit>

    /** Copies page [pageId] (current image and original) under a new id, for merge and split. */
    suspend fun duplicatePage(pageId: String): AppResult<PageDraft>

    /** Drops images staged for an edit that will not be committed. Never touches what a document uses. */
    suspend fun discard(drafts: Collection<PageDraft>)

    /**
     * Makes [pages] (in this order, at least one) the pages of document
     * [documentId]: writes the new PDF with [writePdf] (which reads each page
     * through its `readPage` by page id), records every recipe, removes images
     * no longer used and queues the document for backup.
     */
    suspend fun commit(documentId: String, pages: List<PageDraft>, writePdf: PdfProducer): AppResult<Unit>

    /** Creates a new document from [pages] (imported, merged or split). Returns its id. */
    suspend fun createDocument(
        title: String,
        capturedAt: Instant,
        pages: List<PageDraft>,
        writePdf: PdfProducer,
    ): AppResult<String>
}

internal class DocumentEditorImpl(
    private val dao: DocumentDao,
    private val files: EncryptedFileStore,
    private val syncRunner: SyncRunner,
    private val syncScheduler: SyncScheduler,
    private val dispatchers: AppDispatchers,
    private val clock: TimeSource,
) : DocumentEditor {

    override fun newPageId(): String = UUID.randomUUID().toString()

    override suspend fun pages(documentId: String): AppResult<List<EditablePage>> = localIo {
        dao.getPages(documentId).map { page ->
            val edit = PageEdit.decodeOrNull(page.editJson)
            EditablePage(
                id = page.id,
                width = page.width,
                height = page.height,
                ocrText = page.ocrText,
                // The original is only a starting point when the recipe that turns it into
                // the current image is known. Without one (restored, or edited before its
                // original arrived), re-rendering from the original would drop the crop and
                // look the page already has; such a page is edited from its current image
                // and its original stays untouched as the source photo.
                hasOriginal = edit != null && page.originalFile?.let(files::exists) == true,
                edit = edit,
                image = page.imageFile,
            )
        }
    }

    override suspend fun loadOriginal(pageId: String): AppResult<ByteArray> = localIo {
        val name = dao.getPage(pageId)?.originalFile ?: FileNames.original(pageId)
        files.readBytes(name)
    }

    override suspend fun loadImage(image: String): AppResult<ByteArray> = localIo { files.readBytes(checkHandle(image)) }

    override suspend fun storeRendered(pageId: String, jpeg: ByteArray): AppResult<String> = localIo {
        val name = FileNames.pageRevision(checkId(pageId))
        files.writeBytes(name, jpeg)
        name
    }

    override suspend fun storeOriginal(pageId: String, jpeg: ByteArray): AppResult<Unit> = localIo {
        val name = FileNames.original(checkId(pageId))
        // An existing original is never replaced: it belongs to a saved page.
        check(dao.getPage(pageId) == null) { "page_exists" }
        files.writeBytes(name, jpeg)
    }

    override suspend fun duplicatePage(pageId: String): AppResult<PageDraft> = localIo {
        val source = dao.getPage(pageId) ?: throw IOException("page_missing")
        val copyId = newPageId()
        val image = FileNames.pageRevision(copyId)
        files.copy(source.imageFile, image)
        source.originalFile?.takeIf(files::exists)?.let { files.copy(it, FileNames.original(copyId)) }
        PageDraft(
            id = copyId,
            image = image,
            width = source.width,
            height = source.height,
            ocrText = source.ocrText,
            edit = PageEdit.decodeOrNull(source.editJson),
        )
    }

    override suspend fun discard(drafts: Collection<PageDraft>) {
        withContext(dispatchers.io) {
            val inUse = dao.getAllPageFiles().toSet() + dao.getAllOriginalFiles()
            for (draft in drafts) {
                val staged = listOf(draft.image, FileNames.original(draft.id))
                staged.filter { it !in inUse }.forEach { runCatching { files.delete(it) } }
            }
        }
    }

    override suspend fun commit(
        documentId: String,
        pages: List<PageDraft>,
        writePdf: PdfProducer,
    ): AppResult<Unit> {
        if (pages.isEmpty()) return AppResult.Failure(AppError.Unexpected("no_pages"))
        // No sync runs while the new version is put in place, so an upload of the
        // previous version can never be recorded as the upload of this one.
        return syncRunner.exclusive {
            val before = dao.getById(documentId)
            if (before == null || before.syncState == LocalSyncState.PENDING_DELETE) {
                return@exclusive AppResult.Failure(AppError.LocalStorage)
            }
            val oldPages = dao.getPages(documentId)
            val written = writeDocumentFiles(documentId, pages, oldPages, writePdf)
            val (pdf, entities) = when (written) {
                is AppResult.Success -> written.value
                is AppResult.Failure -> return@exclusive written
            }
            val now = clock.now().toEpochMilli()
            val stored = try {
                dao.replacePages(documentId, entities) { document ->
                    document.copy(
                        pageCount = entities.size,
                        sizeBytes = pdf.sizeBytes,
                        contentSha256 = pdf.sha256,
                        pdfFile = pdf.file,
                        ocrText = entities.joinToString("\n\n") { it.ocrText }.trim(),
                        updatedAt = now,
                        fileUploaded = false,
                        pagesDirty = true,
                        // The server derives a new searchable PDF from the new file.
                        ocrStatus = "NONE",
                        hasSearchablePdf = false,
                        lastErrorCode = null,
                        syncAttempts = 0,
                        syncState = SyncStateMachine.next(document.syncState.toDomain(), SyncEvent.CONTENT_CHANGED)
                            ?.toLocal() ?: document.syncState,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ScanovaLog.error(TAG, "pages_replace_failed", e)
                null
            }
            if (stored == null) {
                withContext(dispatchers.io) { runCatching { files.delete(pdf.file) } }
                return@exclusive AppResult.Failure(AppError.LocalStorage)
            }
            // What the document no longer uses: old PDF, replaced images, originals of removed pages.
            val kept = entities.flatMap { listOfNotNull(it.imageFile, it.originalFile) }.toSet() + pdf.file
            val dropped = oldPages.flatMap { listOfNotNull(it.imageFile, it.originalFile) } + listOfNotNull(before.pdfFile)
            withContext(dispatchers.io) {
                dropped.filter { it !in kept }.forEach { runCatching { files.delete(it) } }
            }
            syncScheduler.requestSync()
            AppResult.Success(Unit)
        }
    }

    override suspend fun createDocument(
        title: String,
        capturedAt: Instant,
        pages: List<PageDraft>,
        writePdf: PdfProducer,
    ): AppResult<String> {
        if (pages.isEmpty()) return AppResult.Failure(AppError.Unexpected("no_pages"))
        val documentId = UUID.randomUUID().toString()
        val (pdf, entities) = when (val written = writeDocumentFiles(documentId, pages, emptyList(), writePdf)) {
            is AppResult.Success -> written.value
            is AppResult.Failure -> return written
        }
        val now = clock.now().toEpochMilli()
        val document = DocumentEntity(
            id = documentId,
            title = title.trim().ifEmpty { "Scan" }.take(MAX_TITLE_LENGTH),
            tags = encodeTags(emptyList()),
            pageCount = entities.size,
            sizeBytes = pdf.sizeBytes,
            contentSha256 = pdf.sha256,
            capturedAt = capturedAt.toEpochMilli(),
            createdAt = now,
            updatedAt = now,
            ocrText = entities.joinToString("\n\n") { it.ocrText }.trim(),
            pdfFile = pdf.file,
            syncState = LocalSyncState.LOCAL_ONLY,
            remoteCreated = false,
            fileUploaded = false,
            metadataDirty = false,
            ocrStatus = "NONE",
            hasSearchablePdf = false,
            lastErrorCode = null,
            syncAttempts = 0,
            pagesDirty = true,
        )
        try {
            dao.insertWithPages(document, entities)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ScanovaLog.error(TAG, "document_insert_failed", e)
            withContext(dispatchers.io) { runCatching { files.delete(pdf.file) } }
            return AppResult.Failure(AppError.LocalStorage)
        }
        syncScheduler.requestSync()
        return AppResult.Success(documentId)
    }

    private class WrittenPdf(val file: String, val sha256: String, val sizeBytes: Long)

    /** A page's original as the new page row records it; [file] is null while it is only in the backup. */
    private class KeptOriginal(val file: String?, val sha256: String, val sizeBytes: Long?, val uploaded: Boolean)

    /** Writes the PDF and builds the page rows (describing originals that are new to this document). */
    private suspend fun writeDocumentFiles(
        documentId: String,
        pages: List<PageDraft>,
        oldPages: List<PageEntity>,
        writePdf: PdfProducer,
    ): AppResult<Pair<WrittenPdf, List<PageEntity>>> = localIo {
        val images = pages.associate { it.id to checkHandle(it.image) }
        require(images.size == pages.size) { "duplicate_page_ids" }
        val pdfName = FileNames.pdfRevision(documentId)
        val (size, sha) = files.write(pdfName) { encrypted ->
            val counting = Sha256CountingOutputStream(encrypted)
            writePdf.write(counting) { pageId -> files.readBytes(images.getValue(pageId)) }
            counting.flush()
            counting.byteCount to counting.sha256Hex()
        }
        val previous = oldPages.associateBy { it.id }
        val entities = pages.mapIndexed { index, draft ->
            val old = previous[draft.id]
            val oldOriginal = old?.originalFile
            val oldSha = old?.originalSha256
            val original = when {
                // On this phone, as recorded: whatever the server already has stays valid.
                oldOriginal != null && oldSha != null && files.exists(oldOriginal) ->
                    KeptOriginal(oldOriginal, oldSha, old.originalSizeBytes, uploaded = old.originalUploaded)
                // Only in the backup so far (a restore downloads it later): keep what identifies
                // it, or the restore could never fetch it and the backup would lose track of it.
                oldSha != null && old.originalUploaded ->
                    KeptOriginal(file = null, oldSha, old.originalSizeBytes, uploaded = true)
                // A page added in this edit, with its original staged under its id.
                files.exists(FileNames.original(draft.id)) ->
                    files.describe(FileNames.original(draft.id))
                        .let { KeptOriginal(it.file, it.sha256, it.sizeBytes, uploaded = false) }
                else -> null
            }
            PageEntity(
                id = draft.id,
                documentId = documentId,
                position = index,
                imageFile = images.getValue(draft.id),
                width = draft.width,
                height = draft.height,
                ocrText = draft.ocrText,
                // An unchanged image was already read by the text indexer: don't read it again after every edit.
                textCheckedImage = old?.textCheckedImage?.takeIf { it == images.getValue(draft.id) },
                originalFile = original?.file,
                originalSha256 = original?.sha256,
                originalSizeBytes = original?.sizeBytes,
                originalUploaded = original?.uploaded == true,
                editJson = draft.edit?.let(PageEdit::encode),
            )
        }
        WrittenPdf(pdfName, sha, size) to entities
    }

    /** A handle must name a page image in the store, never anything else. */
    private fun checkHandle(image: String): String {
        require(image.startsWith("${FileNames.PAGES_DIR}/") && !image.contains("..")) { "bad_image_handle" }
        return image
    }

    private fun checkId(id: String): String = UUID.fromString(id).toString().also { require(it == id.lowercase()) }

    private suspend fun <R> localIo(block: suspend () -> R): AppResult<R> = withContext(dispatchers.io) {
        try {
            AppResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ScanovaLog.warn(TAG, "edit_io_failed", e)
            AppResult.Failure(AppError.LocalStorage)
        } catch (e: GeneralSecurityException) {
            ScanovaLog.warn(TAG, "edit_crypto_failed", e)
            AppResult.Failure(AppError.LocalStorage)
        } catch (e: IllegalArgumentException) {
            ScanovaLog.warn(TAG, "edit_rejected", e)
            AppResult.Failure(AppError.LocalStorage)
        } catch (e: IllegalStateException) {
            ScanovaLog.warn(TAG, "edit_rejected", e)
            AppResult.Failure(AppError.LocalStorage)
        }
    }

    private companion object {
        const val TAG = "DocumentEditor"
        const val MAX_TITLE_LENGTH = 200
    }
}
