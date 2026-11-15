package app.scanova.core.data.scan

import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.common.util.Sha256CountingOutputStream
import app.scanova.core.common.util.TimeSource
import app.scanova.core.data.documents.encodeTags
import app.scanova.core.data.storage.FileNames
import app.scanova.core.data.sync.SyncScheduler
import app.scanova.core.database.dao.DocumentDao
import app.scanova.core.database.entity.DocumentEntity
import app.scanova.core.database.entity.LocalSyncState
import app.scanova.core.database.entity.PageEntity
import app.scanova.core.security.file.EncryptedFileStore
import java.io.IOException
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withContext

/** A page of a scan in progress, ready to become part of a document. */
data class ScannedPage(
    val id: String,
    val width: Int,
    val height: Int,
    val ocrText: String,
)

data class ScannedDocument(
    val title: String,
    val capturedAt: Instant,
    val pages: List<ScannedPage>,
)

/** Writes a PDF to [output], reading page images through [readPage]. */
fun interface PdfProducer {
    fun write(output: OutputStream, readPage: (pageId: String) -> ByteArray)
}

/**
 * Storage for the scan flow. Captures and finished pages are written straight
 * into the encrypted file store; an image never touches the disk unencrypted.
 */
interface ScanRepository {
    /** A new random id for a page. */
    fun newPageId(): String

    /** Stores the untouched camera capture of a page so it can be cropped again later. */
    suspend fun storeCapture(pageId: String, jpeg: ByteArray): AppResult<Unit>

    suspend fun loadCapture(pageId: String): AppResult<ByteArray>

    /** Stores the finished (cropped, filtered) image of a page. */
    suspend fun storePage(pageId: String, jpeg: ByteArray): AppResult<Unit>

    suspend fun loadPage(pageId: String): AppResult<ByteArray>

    /** Removes a page that will not be used (deleted or replaced during review). */
    suspend fun discardPage(pageId: String)

    /**
     * Turns the scan into a document: [writePdf] produces the PDF directly
     * into an encrypted file, the document is stored locally and queued for
     * upload. Returns the new document's id.
     *
     * [writePdf] runs on an I/O thread. It receives the encrypting output
     * stream and a function that decrypts one finished page image at a time,
     * so a long document never has all its pages in memory together.
     */
    suspend fun saveDocument(document: ScannedDocument, writePdf: PdfProducer): AppResult<String>
}

internal class ScanRepositoryImpl(
    private val dao: DocumentDao,
    private val files: EncryptedFileStore,
    private val syncScheduler: SyncScheduler,
    private val dispatchers: AppDispatchers,
    private val clock: TimeSource,
) : ScanRepository {

    override fun newPageId(): String = UUID.randomUUID().toString()

    override suspend fun storeCapture(pageId: String, jpeg: ByteArray): AppResult<Unit> =
        localIo { files.writeBytes(FileNames.scratch(pageId), jpeg) }

    override suspend fun loadCapture(pageId: String): AppResult<ByteArray> =
        localIo { files.readBytes(FileNames.scratch(pageId)) }

    override suspend fun storePage(pageId: String, jpeg: ByteArray): AppResult<Unit> =
        localIo { files.writeBytes(FileNames.page(pageId), jpeg) }

    override suspend fun loadPage(pageId: String): AppResult<ByteArray> =
        localIo { files.readBytes(FileNames.page(pageId)) }

    override suspend fun discardPage(pageId: String) {
        withContext(dispatchers.io) {
            files.delete(FileNames.scratch(pageId))
            files.delete(FileNames.page(pageId))
        }
    }

    override suspend fun saveDocument(document: ScannedDocument, writePdf: PdfProducer): AppResult<String> {
        if (document.pages.isEmpty()) return AppResult.Failure(AppError.Unexpected("no_pages"))
        // Generated on the phone: this is also the document's id on the server,
        // which is what makes the upload idempotent (docs/ARCHITECTURE.md §6).
        val documentId = UUID.randomUUID().toString()
        val pdfName = FileNames.pdf(documentId)

        val written = localIo {
            files.write(pdfName) { encrypted ->
                val counting = Sha256CountingOutputStream(encrypted)
                writePdf.write(counting) { pageId -> files.readBytes(FileNames.page(pageId)) }
                counting.flush()
                counting.byteCount to counting.sha256Hex()
            }
        }
        val (sizeBytes, sha256) = when (written) {
            is AppResult.Success -> written.value
            is AppResult.Failure -> return written
        }

        val now = clock.now().toEpochMilli()
        val entity = DocumentEntity(
            id = documentId,
            title = document.title.trim().ifEmpty { "Scan" }.take(MAX_TITLE_LENGTH),
            tags = encodeTags(emptyList()),
            pageCount = document.pages.size,
            sizeBytes = sizeBytes,
            contentSha256 = sha256,
            capturedAt = document.capturedAt.toEpochMilli(),
            createdAt = now,
            updatedAt = now,
            ocrText = document.pages.joinToString("\n\n") { it.ocrText }.trim(),
            pdfFile = pdfName,
            syncState = LocalSyncState.LOCAL_ONLY,
            remoteCreated = false,
            fileUploaded = false,
            metadataDirty = false,
            ocrStatus = "NONE",
            hasSearchablePdf = false,
            lastErrorCode = null,
            syncAttempts = 0,
        )
        val pages = document.pages.mapIndexed { index, page ->
            PageEntity(
                id = page.id,
                documentId = documentId,
                position = index,
                imageFile = FileNames.page(page.id),
                width = page.width,
                height = page.height,
                ocrText = page.ocrText,
            )
        }
        try {
            dao.insertWithPages(entity, pages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ScanovaLog.error(TAG, "document_insert_failed", e)
            withContext(dispatchers.io) { files.delete(pdfName) }
            return AppResult.Failure(AppError.LocalStorage)
        }

        // The original captures were only needed for re-cropping during review.
        withContext(dispatchers.io) {
            document.pages.forEach { files.delete(FileNames.scratch(it.id)) }
        }
        syncScheduler.requestSync()
        return AppResult.Success(documentId)
    }

    private suspend fun <R> localIo(block: () -> R): AppResult<R> = withContext(dispatchers.io) {
        try {
            AppResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ScanovaLog.warn(TAG, "encrypted_io_failed", e)
            AppResult.Failure(AppError.LocalStorage)
        } catch (e: GeneralSecurityException) {
            ScanovaLog.warn(TAG, "encryption_failed", e)
            AppResult.Failure(AppError.LocalStorage)
        }
    }

    private companion object {
        const val TAG = "Scan"
        const val MAX_TITLE_LENGTH = 200
    }
}
