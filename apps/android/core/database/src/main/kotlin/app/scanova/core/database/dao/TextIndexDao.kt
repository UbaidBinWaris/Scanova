package app.scanova.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import app.scanova.core.database.entity.DocumentEntity
import app.scanova.core.database.entity.PageEntity

/**
 * Queries of the background text indexer (`core:data` …/indexing): pages
 * whose text was never read on this phone, and documents whose text the
 * on-device rules have not looked at yet.
 *
 * A page "needs text" when its text is empty and its current image is not
 * the one last read ([PageEntity.textCheckedImage]); pages of documents being
 * deleted or waiting for a page restore are left alone.
 */
@Dao
abstract class TextIndexDao {

    @Query(
        """
        SELECT p.* FROM pages AS p JOIN documents AS d ON d.id = p.documentId
        WHERE p.ocrText = '' AND (p.textCheckedImage IS NULL OR p.textCheckedImage != p.imageFile)
          AND d.syncState != 'PENDING_DELETE' AND d.pagesStale = 0
        ORDER BY d.updatedAt DESC, p.position
        LIMIT :limit
        """,
    )
    abstract suspend fun pagesNeedingText(limit: Int): List<PageEntity>

    /**
     * Documents with text that rules older than [version] (or none) have
     * looked at, once none of their pages still waits for its text.
     */
    @Query(
        """
        SELECT d.* FROM documents AS d
        WHERE d.analysisVersion < :version AND d.ocrText != '' AND d.syncState != 'PENDING_DELETE'
          AND NOT EXISTS (
            SELECT 1 FROM pages AS p
            WHERE p.documentId = d.id AND p.ocrText = ''
              AND (p.textCheckedImage IS NULL OR p.textCheckedImage != p.imageFile)
          )
        ORDER BY d.updatedAt DESC
        LIMIT :limit
        """,
    )
    abstract suspend fun documentsToAnalyse(version: Int, limit: Int): List<DocumentEntity>

    @Query("SELECT * FROM pages WHERE id = :pageId")
    abstract suspend fun page(pageId: String): PageEntity?

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY position")
    abstract suspend fun pages(documentId: String): List<PageEntity>

    @Query("SELECT * FROM documents WHERE id = :id")
    abstract suspend fun document(id: String): DocumentEntity?

    @Update
    abstract suspend fun updatePage(page: PageEntity)

    @Update
    abstract suspend fun updateDocument(document: DocumentEntity)

    /**
     * Records that page [pageId]'s image [imageFile] was read and gave
     * [text] (kept only if the page has no text by now), then lets [change]
     * update the document from its pages before and after — all in one
     * transaction. Writes nothing when the page is gone or shows another image
     * by now (edited or restored meanwhile). Returns whether it wrote.
     */
    @Transaction
    open suspend fun recordPageText(
        pageId: String,
        imageFile: String,
        text: String,
        change: (document: DocumentEntity, before: List<PageEntity>, after: List<PageEntity>) -> DocumentEntity,
    ): Boolean {
        val page = page(pageId) ?: return false
        if (page.imageFile != imageFile) return false
        val before = pages(page.documentId)
        val updated = page.copy(ocrText = page.ocrText.ifEmpty { text }, textCheckedImage = imageFile)
        if (updated != page) updatePage(updated)
        val document = document(page.documentId) ?: return true
        val after = before.map { if (it.id == pageId) updated else it }
        val changed = change(document, before, after)
        if (changed != document) updateDocument(changed)
        return true
    }

    /**
     * Read-modify-write of a document in one transaction; [change] returns
     * null to leave it as it is. Returns the stored row, or null when nothing
     * was written.
     */
    @Transaction
    open suspend fun modifyDocument(id: String, change: (DocumentEntity) -> DocumentEntity?): DocumentEntity? {
        val current = document(id) ?: return null
        val changed = change(current) ?: return null
        if (changed != current) updateDocument(changed)
        return changed
    }
}
