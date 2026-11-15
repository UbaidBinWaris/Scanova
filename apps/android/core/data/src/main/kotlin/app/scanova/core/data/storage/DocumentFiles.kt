package app.scanova.core.data.storage

import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.database.dao.DocumentDao
import app.scanova.core.security.file.EncryptedFileStore
import kotlinx.coroutines.withContext

/**
 * Keeps database rows and encrypted files in step: a document is removed
 * together with its page images and PDF, and files that no row refers to
 * (left behind by a crash or an abandoned scan) are swept away.
 */
internal class DocumentFiles(
    private val documents: DocumentDao,
    private val files: EncryptedFileStore,
    private val dispatchers: AppDispatchers,
) {
    /** Removes a document from this phone: row, pages, page images and PDF. */
    suspend fun deleteDocument(id: String) {
        val document = documents.getById(id) ?: return
        val pages = documents.getPages(id)
        documents.deleteById(id)
        withContext(dispatchers.io) {
            pages.forEach { files.delete(it.imageFile) }
            document.pdfFile?.let(files::delete)
        }
    }

    /** Removes the encrypted files of a document but keeps its row (used while a delete is pending). */
    suspend fun deleteFilesOnly(id: String) {
        val document = documents.getById(id) ?: return
        val pages = documents.getPages(id)
        withContext(dispatchers.io) {
            pages.forEach { files.delete(it.imageFile) }
            document.pdfFile?.let(files::delete)
        }
    }

    /**
     * Deletes every stored file that is not referenced by the database, and all
     * scratch captures. Run at app start, when no scan is in progress.
     */
    suspend fun sweepOrphans() {
        val referenced = (documents.getAllPageFiles() + documents.getAllPdfFiles()).toSet()
        withContext(dispatchers.io) {
            var removed = 0
            for (directory in listOf(FileNames.PAGES_DIR, FileNames.PDF_DIR)) {
                for (name in files.list(directory)) {
                    if (name !in referenced && files.delete(name)) removed++
                }
            }
            for (name in files.list(FileNames.SCRATCH_DIR)) {
                if (files.delete(name)) removed++
            }
            if (removed > 0) ScanovaLog.info(TAG, "orphan_files_removed count=$removed")
        }
    }

    private companion object {
        const val TAG = "DocumentFiles"
    }
}
