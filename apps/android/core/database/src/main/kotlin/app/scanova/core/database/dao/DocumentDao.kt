package app.scanova.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import app.scanova.core.database.entity.DocumentEntity
import app.scanova.core.database.entity.PageEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class DocumentDao {

    // ── Reads for the UI ────────────────────────────────────────────────

    @Query("SELECT * FROM documents WHERE syncState != 'PENDING_DELETE' ORDER BY capturedAt DESC, id")
    abstract fun observeVisible(): Flow<List<DocumentEntity>>

    /**
     * Full-text search. [match] must be an FTS expression built from user
     * input by the data layer, never raw user text.
     */
    @Query(
        """
        SELECT d.* FROM documents AS d
        JOIN documents_fts AS f ON f.docid = d.rowid
        WHERE documents_fts MATCH :match AND d.syncState != 'PENDING_DELETE'
        ORDER BY d.capturedAt DESC, d.id
        """,
    )
    abstract fun observeSearch(match: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id AND syncState != 'PENDING_DELETE'")
    abstract fun observeById(id: String): Flow<DocumentEntity?>

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY position")
    abstract fun observePages(documentId: String): Flow<List<PageEntity>>

    // ── Reads for sync ──────────────────────────────────────────────────

    @Query("SELECT * FROM documents WHERE id = :id")
    abstract suspend fun getById(id: String): DocumentEntity?

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY position")
    abstract suspend fun getPages(documentId: String): List<PageEntity>

    /** Documents with something to send: not yet uploaded, failed earlier, or edited since. */
    @Query(
        """
        SELECT * FROM documents
        WHERE syncState IN ('LOCAL_ONLY', 'UPLOADING', 'FAILED')
           OR (syncState = 'SYNCED' AND metadataDirty = 1)
        ORDER BY createdAt
        """,
    )
    abstract suspend fun getPendingPush(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE syncState = 'PENDING_DELETE'")
    abstract suspend fun getPendingDelete(): List<DocumentEntity>

    @Query(
        """
        SELECT COUNT(*) FROM documents
        WHERE syncState IN ('LOCAL_ONLY', 'UPLOADING', 'FAILED', 'PENDING_DELETE')
           OR (syncState = 'SYNCED' AND metadataDirty = 1)
        """,
    )
    abstract fun observePendingCount(): Flow<Int>

    // ── Writes ──────────────────────────────────────────────────────────

    @Insert
    abstract suspend fun insert(document: DocumentEntity)

    @Insert
    abstract suspend fun insertPages(pages: List<PageEntity>)

    @Update
    abstract suspend fun update(document: DocumentEntity)

    /** Pages go with the document (foreign key, ON DELETE CASCADE). */
    @Query("DELETE FROM documents WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    @Query("DELETE FROM documents")
    abstract suspend fun deleteAll()

    @Transaction
    open suspend fun insertWithPages(document: DocumentEntity, pages: List<PageEntity>) {
        insert(document)
        insertPages(pages)
    }

    /**
     * Read-modify-write in one transaction, so a sync result and a user edit
     * that land at the same moment cannot overwrite each other. Returns the
     * stored row, or null when the document does not exist.
     */
    @Transaction
    open suspend fun modify(id: String, change: (DocumentEntity) -> DocumentEntity): DocumentEntity? {
        val current = getById(id) ?: return null
        val updated = change(current)
        if (updated != current) update(updated)
        return updated
    }

    @Query("DELETE FROM pages WHERE documentId = :documentId")
    abstract suspend fun deletePages(documentId: String)

    @Update
    abstract suspend fun updatePage(page: PageEntity)

    @Query("SELECT * FROM pages WHERE id = :pageId")
    abstract suspend fun getPage(pageId: String): PageEntity?

    /**
     * Replaces a document's page list and its row in one transaction: an edit
     * either lands completely or not at all. Returns the stored document row, or
     * null when the document no longer exists (nothing is written then).
     */
    @Transaction
    open suspend fun replacePages(
        documentId: String,
        pages: List<PageEntity>,
        change: (DocumentEntity) -> DocumentEntity,
    ): DocumentEntity? {
        val current = getById(documentId) ?: return null
        val updated = change(current)
        deletePages(documentId)
        insertPages(pages)
        if (updated != current) update(updated)
        return updated
    }

    // ── Pages: backup to and restore from the server ────────────────────

    /** Synced documents whose page list changed here and still has to be sent. */
    @Query("SELECT * FROM documents WHERE syncState = 'SYNCED' AND pagesDirty = 1 ORDER BY createdAt")
    abstract suspend fun getPagesToPush(): List<DocumentEntity>

    /** Synced documents whose pages here are missing or out of date, most recently changed first. */
    @Query(
        """
        SELECT * FROM documents
        WHERE syncState = 'SYNCED' AND pagesStale = 1 AND pagesDirty = 0
        ORDER BY updatedAt DESC
        """,
    )
    abstract suspend fun getPagesToRestore(): List<DocumentEntity>

    /** Queues every synced document that has no page on this phone for a page restore. */
    @Query(
        """
        UPDATE documents SET pagesStale = 1
        WHERE syncState = 'SYNCED' AND fileUploaded = 1 AND pagesDirty = 0 AND pagesStale = 0
          AND NOT EXISTS (SELECT 1 FROM pages WHERE pages.documentId = documents.id)
        """,
    )
    abstract suspend fun markPagelessDocumentsStale(): Int

    /** Pages whose original is stored on the server but not (yet) on this phone. */
    @Query(
        """
        SELECT p.* FROM pages AS p JOIN documents AS d ON d.id = p.documentId
        WHERE d.syncState = 'SYNCED' AND d.pagesDirty = 0 AND d.pagesStale = 0
          AND p.originalFile IS NULL AND p.originalUploaded = 1 AND p.originalSha256 IS NOT NULL
        ORDER BY d.updatedAt DESC, p.position
        """,
    )
    abstract suspend fun getMissingOriginals(): List<PageEntity>

    /** How many of [pageIds] belong to a document other than [documentId]. */
    @Query("SELECT COUNT(*) FROM pages WHERE id IN (:pageIds) AND documentId != :documentId")
    abstract suspend fun countPagesElsewhere(documentId: String, pageIds: List<String>): Int

    /** Read-modify-write of one page in a transaction. Returns the stored row, or null when the page is gone. */
    @Transaction
    open suspend fun modifyPage(pageId: String, change: (PageEntity) -> PageEntity): PageEntity? {
        val current = getPage(pageId) ?: return null
        val updated = change(current)
        if (updated != current) updatePage(updated)
        return updated
    }

    /**
     * Replaces a document's pages with restored ones in one transaction, if
     * [change] accepts the document as it is now (it returns null to refuse:
     * nothing is written then). Returns the pages that were replaced, or null.
     */
    @Transaction
    open suspend fun restorePages(
        documentId: String,
        pages: List<PageEntity>,
        change: (DocumentEntity) -> DocumentEntity?,
    ): List<PageEntity>? {
        val current = getById(documentId) ?: return null
        val updated = change(current) ?: return null
        val previous = getPages(documentId)
        deletePages(documentId)
        insertPages(pages)
        if (updated != current) update(updated)
        return previous
    }

    // ── File bookkeeping ────────────────────────────────────────────────

    @Query("SELECT imageFile FROM pages")
    abstract suspend fun getAllPageFiles(): List<String>

    @Query("SELECT originalFile FROM pages WHERE originalFile IS NOT NULL")
    abstract suspend fun getAllOriginalFiles(): List<String>

    @Query("SELECT pdfFile FROM documents WHERE pdfFile IS NOT NULL")
    abstract suspend fun getAllPdfFiles(): List<String>
}
