package app.scanova.core.data.storage

import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.database.dao.DocumentDao
import app.scanova.core.database.entity.PageEntity
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
    /** Removes a document from this phone: row, pages, page images (current and original) and PDF. */
    suspend fun deleteDocument(id: String) {
        val document = documents.getById(id) ?: return
        val pages = documents.getPages(id)
        documents.deleteById(id)
        withContext(dispatchers.io) {
            pages.forEach { page -> page.files().forEach(files::delete) }
            document.pdfFile?.let(files::delete)
        }
    }

    /** Removes the encrypted files of a document but keeps its row (used while a delete is pending). */
    suspend fun deleteFilesOnly(id: String) {
        val document = documents.getById(id) ?: return
        val pages = documents.getPages(id)
        withContext(dispatchers.io) {
            pages.forEach { page -> page.files().forEach(files::delete) }
            document.pdfFile?.let(files::delete)
        }
    }

    /**
     * Deletes every stored file that is not referenced by the database, and all
     * scratch captures. Run at app start, when no scan or edit is in progress.
     */
    suspend fun sweepOrphans(nowMillis: Long = System.currentTimeMillis()) {
        val referenced = (
            documents.getAllPageFiles() + documents.getAllOriginalFiles() + documents.getAllPdfFiles()
            ).toSet()
        withContext(dispatchers.io) {
            // A scan, edit or import started right after launch stages files before any row
            // refers to them; anything written recently is left for the next start.
            val settled = { name: String -> nowMillis - files.lastModified(name) > RECENT_GRACE_MILLIS }
            var removed = 0
            for (directory in listOf(FileNames.PAGES_DIR, FileNames.ORIGINALS_DIR, FileNames.PDF_DIR)) {
                for (name in files.list(directory)) {
                    if (name !in referenced && settled(name) && files.delete(name)) removed++
                }
            }
            for (name in files.list(FileNames.SCRATCH_DIR)) {
                if (settled(name) && files.delete(name)) removed++
            }
            if (removed > 0) ScanovaLog.info(TAG, "orphan_files_removed count=$removed")
        }
    }

    private fun PageEntity.files(): List<String> = listOfNotNull(imageFile, originalFile)

    private companion object {
        const val TAG = "DocumentFiles"

        /** Files younger than this may belong to work in progress and are never swept. */
        const val RECENT_GRACE_MILLIS = 15 * 60 * 1000L
    }
}
