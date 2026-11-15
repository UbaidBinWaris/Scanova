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

    // ── File bookkeeping ────────────────────────────────────────────────

    @Query("SELECT imageFile FROM pages")
    abstract suspend fun getAllPageFiles(): List<String>

    @Query("SELECT pdfFile FROM documents WHERE pdfFile IS NOT NULL")
    abstract suspend fun getAllPdfFiles(): List<String>
}
