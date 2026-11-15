package app.scanova.core.data.documents

import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.common.util.TimeSource
import app.scanova.core.data.model.Document
import app.scanova.core.data.model.DocumentPage
import app.scanova.core.data.storage.DocumentFiles
import app.scanova.core.data.storage.ExportFiles
import app.scanova.core.data.storage.FileNames
import app.scanova.core.data.sync.SyncEvent
import app.scanova.core.data.sync.SyncOutcome
import app.scanova.core.data.sync.SyncRunner
import app.scanova.core.data.sync.SyncScheduler
import app.scanova.core.data.sync.SyncStateMachine
import app.scanova.core.data.util.normalizeTags
import app.scanova.core.database.dao.DocumentDao
import app.scanova.core.database.entity.DocumentEntity
import app.scanova.core.network.ScanovaRemote
import app.scanova.core.security.file.EncryptedFileStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The user's documents: the local, encrypted library and its link to the server. */
interface DocumentRepository {
    /** Documents matching [query] (all of them when it is blank), newest first. */
    fun observeDocuments(query: String): Flow<List<Document>>

    fun observeDocument(id: String): Flow<Document?>

    /**
     * Page images of a document. Documents from the server get theirs in the
     * background after a sync (page restore); until then the list is empty.
     */
    fun observePages(id: String): Flow<List<DocumentPage>>

    /** Number of documents with something still to be sent to the server. */
    fun observePendingSyncCount(): Flow<Int>

    suspend fun rename(id: String, title: String): AppResult<Unit>

    suspend fun setTags(id: String, tags: List<String>): AppResult<Unit>

    suspend fun delete(id: String): AppResult<Unit>

    /** Queues a document whose upload was rejected for another attempt. */
    suspend fun retryUpload(id: String): AppResult<Unit>

    /** Synchronises with the server now (push, then pull) and waits for the result. */
    suspend fun refresh(): AppResult<Unit>

    /** Decrypts one page image into memory (JPEG bytes). */
    suspend fun loadPageImage(pageId: String): AppResult<ByteArray>

    /**
     * Decrypts the first page of a document scanned on this phone (JPEG bytes),
     * for a preview in the library. Fails with [AppError.NotFound] for documents
     * that have no page images here.
     */
    suspend fun loadCoverImage(documentId: String): AppResult<ByteArray>

    /**
     * Hands the decrypted PDF to [block] as a stream. A document that only
     * exists on the server is downloaded first and kept, encrypted, for next time.
     */
    suspend fun <R> readPdf(id: String, block: suspend (InputStream) -> R): AppResult<R>

    /** Writes the decrypted PDF to [output], a destination the user chose. Nothing is left on disk here. */
    suspend fun exportTo(id: String, output: OutputStream): AppResult<Unit>

    /**
     * Writes the decrypted PDF to a temporary file in the app's private cache so
     * it can be shared with another app. The caller must call [deleteExport]
     * when the share is over.
     */
    suspend fun exportForSharing(id: String): AppResult<File>

    suspend fun deleteExport(file: File)

    /** Removes temporary share files and unreferenced encrypted files left by an interrupted session. */
    suspend fun cleanUpLeftovers()
}

internal class DocumentRepositoryImpl(
    private val dao: DocumentDao,
    private val files: EncryptedFileStore,
    private val documentFiles: DocumentFiles,
    private val exports: ExportFiles,
    private val remote: ScanovaRemote,
    private val syncScheduler: SyncScheduler,
    private val syncRunner: SyncRunner,
    private val dispatchers: AppDispatchers,
    private val clock: TimeSource,
) : DocumentRepository {

    override fun observeDocuments(query: String): Flow<List<Document>> {
        val match = SearchQuery.toFtsMatch(query)
        val source = if (match == null) dao.observeVisible() else dao.observeSearch(match)
        return source.map { rows -> rows.map(DocumentEntity::toDomain) }
    }

    override fun observeDocument(id: String): Flow<Document?> = dao.observeById(id).map { it?.toDomain() }

    override fun observePages(id: String): Flow<List<DocumentPage>> =
        dao.observePages(id).map { pages -> pages.map { it.toDomain() } }

    override fun observePendingSyncCount(): Flow<Int> = dao.observePendingCount()

    override suspend fun rename(id: String, title: String): AppResult<Unit> {
        val clean = title.trim().take(MAX_TITLE_LENGTH)
        if (clean.isEmpty()) return AppResult.Failure(AppError.Unexpected("blank_title"))
        return edit(id) { it.copy(title = clean) }
    }

    override suspend fun setTags(id: String, tags: List<String>): AppResult<Unit> {
        val clean = normalizeTags(tags).take(MAX_TAGS).map { it.take(MAX_TAG_LENGTH) }
        return edit(id) { it.copy(tags = encodeTags(clean)) }
    }

    private suspend fun edit(id: String, change: (DocumentEntity) -> DocumentEntity): AppResult<Unit> {
        val updated = dao.modify(id) { current ->
            val changed = change(current)
            if (changed == current) {
                current
            } else {
                changed.copy(
                    updatedAt = clock.now().toEpochMilli(),
                    // Before the document exists on the server the create request carries the
                    // latest values. Afterwards the edit has to be sent as an update.
                    metadataDirty = current.metadataDirty || current.remoteCreated,
                )
            }
        } ?: return AppResult.Failure(AppError.NotFound)
        if (updated.metadataDirty) syncScheduler.requestSync()
        return AppResult.Success(Unit)
    }

    override suspend fun delete(id: String): AppResult<Unit> {
        var deleteLocallyNow = false
        val updated = dao.modify(id) { current ->
            val next = SyncStateMachine.next(current.syncState.toDomain(), SyncEvent.DELETE_REQUESTED)
                ?: return@modify current
            // Never sent and not being sent: the server does not know it, nothing to confirm.
            deleteLocallyNow = !current.remoteCreated && current.syncState.toDomain() != UPLOADING
            current.copy(syncState = next.toLocal(), updatedAt = clock.now().toEpochMilli())
        } ?: return AppResult.Failure(AppError.NotFound)

        if (deleteLocallyNow) {
            documentFiles.deleteDocument(id)
        } else {
            // The content leaves the phone immediately; only the row waits for the server.
            documentFiles.deleteFilesOnly(updated.id)
            syncScheduler.requestSync()
        }
        return AppResult.Success(Unit)
    }

    override suspend fun retryUpload(id: String): AppResult<Unit> {
        dao.modify(id) { current ->
            val next = SyncStateMachine.next(current.syncState.toDomain(), SyncEvent.RETRY_REQUESTED)
                ?: return@modify current
            current.copy(syncState = next.toLocal(), lastErrorCode = null, syncAttempts = 0)
        } ?: return AppResult.Failure(AppError.NotFound)
        syncScheduler.requestSync()
        return AppResult.Success(Unit)
    }

    override suspend fun refresh(): AppResult<Unit> {
        val report = try {
            // Restoring pages of documents from the server can take minutes; the background worker does it.
            syncRunner.run(restorePages = false)
        } catch (e: IOException) {
            return AppResult.Failure(AppError.LocalStorage)
        }
        return when (report.outcome) {
            SyncOutcome.COMPLETE -> {
                if (report.restorePending) syncScheduler.requestSync()
                AppResult.Success(Unit)
            }
            SyncOutcome.RETRY_LATER -> {
                // The background worker keeps trying with backoff.
                syncScheduler.requestSync()
                AppResult.Failure(report.error ?: AppError.NoConnection)
            }
            SyncOutcome.AUTH_REQUIRED -> AppResult.Failure(report.error ?: AppError.SignedOut)
        }
    }

    override suspend fun loadPageImage(pageId: String): AppResult<ByteArray> {
        // Edited and restored pages have a revision name; only a fresh scan's image is named after its page.
        val name = dao.getPage(pageId)?.imageFile ?: FileNames.page(pageId)
        return localIo { files.readBytes(name) }
    }

    override suspend fun loadCoverImage(documentId: String): AppResult<ByteArray> {
        val first = dao.getPages(documentId).firstOrNull() ?: return AppResult.Failure(AppError.NotFound)
        return localIo { files.readBytes(first.imageFile) }
    }

    override suspend fun <R> readPdf(id: String, block: suspend (InputStream) -> R): AppResult<R> {
        val name = when (val ensured = ensurePdfStored(id)) {
            is AppResult.Success -> ensured.value
            is AppResult.Failure -> return ensured
        }
        return localIo { files.openRead(name).use { input -> block(input) } }
    }

    override suspend fun exportTo(id: String, output: OutputStream): AppResult<Unit> =
        readPdf(id) { input -> output.use { input.copyTo(it) } }.asUnit()

    override suspend fun exportForSharing(id: String): AppResult<File> {
        val document = dao.getById(id) ?: return AppResult.Failure(AppError.NotFound)
        val target = withContext(dispatchers.io) { exports.newFile(exportFileName(document.title)) }
        val written = readPdf(id) { input -> target.outputStream().use { input.copyTo(it) } }
        return when (written) {
            is AppResult.Success -> AppResult.Success(target)
            is AppResult.Failure -> {
                withContext(dispatchers.io) { exports.delete(target) }
                written
            }
        }
    }

    override suspend fun deleteExport(file: File) = withContext(dispatchers.io) { exports.delete(file) }

    override suspend fun cleanUpLeftovers() {
        withContext(dispatchers.io) { exports.deleteAll() }
        try {
            // Never while a sync runs: a page restore stores files before the rows that use them.
            syncRunner.exclusive { documentFiles.sweepOrphans() }
        } catch (e: IOException) {
            ScanovaLog.warn(TAG, "orphan_sweep_failed", e)
        }
    }

    /** Returns the file-store name of the document's PDF, downloading it first when needed. */
    private suspend fun ensurePdfStored(id: String): AppResult<String> {
        val document = dao.getById(id) ?: return AppResult.Failure(AppError.NotFound)
        document.pdfFile?.let { stored ->
            if (withContext(dispatchers.io) { files.exists(stored) }) return AppResult.Success(stored)
        }
        if (!document.fileUploaded) return AppResult.Failure(AppError.LocalStorage)

        val name = FileNames.pdf(id)
        // The searchable variant carries the server's OCR text layer; prefer it when it exists.
        val downloaded = remote.downloadFile(id, searchable = document.hasSearchablePdf) { input ->
            withContext(dispatchers.io) {
                files.write(name) { output -> input.copyTo(output) }
            }
        }
        return when (downloaded) {
            is AppResult.Failure -> downloaded
            is AppResult.Success -> {
                dao.modify(id) { it.copy(pdfFile = name) }
                AppResult.Success(name)
            }
        }
    }

    private suspend fun <R> localIo(block: suspend () -> R): AppResult<R> = withContext(dispatchers.io) {
        try {
            AppResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ScanovaLog.warn(TAG, "local_read_failed", e)
            AppResult.Failure(AppError.LocalStorage)
        } catch (e: GeneralSecurityException) {
            ScanovaLog.warn(TAG, "local_decrypt_failed", e)
            AppResult.Failure(AppError.LocalStorage)
        }
    }

    private fun <T> AppResult<T>.asUnit(): AppResult<Unit> = when (this) {
        is AppResult.Success -> AppResult.Success(Unit)
        is AppResult.Failure -> this
    }

    private companion object {
        const val TAG = "Documents"
        const val MAX_TITLE_LENGTH = 200
        const val MAX_TAGS = 20
        const val MAX_TAG_LENGTH = 40
        val UPLOADING = app.scanova.core.data.model.SyncState.UPLOADING
    }
}

/** The file name to suggest when the user saves a copy of a document titled [title]. */
fun exportDisplayName(title: String): String = exportFileName(title)

/** A file name other apps can show: the title without characters that file systems reject. */
internal fun exportFileName(title: String): String {
    val cleaned = title
        .map { if (it.isLetterOrDigit() || it in " -_().") it else ' ' }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('.')
        .take(MAX_EXPORT_NAME_LENGTH)
        .trim()
    return (cleaned.ifEmpty { "Scanova document" }) + ".pdf"
}

private const val MAX_EXPORT_NAME_LENGTH = 80
