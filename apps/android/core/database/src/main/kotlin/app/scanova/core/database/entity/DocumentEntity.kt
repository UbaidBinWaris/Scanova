package app.scanova.core.database.entity

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * One scanned document. [id] is the UUID generated on the phone, which is also
 * the document's public id on the server, so uploads are idempotent.
 *
 * All times are epoch milliseconds (UTC).
 */
@Entity(
    tableName = "documents",
    indices = [
        Index("syncState"),
        Index("capturedAt"),
    ],
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** Tags joined with a line feed. A tag never contains a line feed. */
    val tags: String,
    val pageCount: Int,
    /** Size of the PDF in bytes, when known. */
    val sizeBytes: Long?,
    /** SHA-256 of the PDF as lower-case hex, when known. */
    val contentSha256: String?,
    val capturedAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    /** Text recognised on the phone, all pages. Feeds local search and `clientOcrText`. */
    val ocrText: String,
    /** Name of the encrypted PDF in the file store; null while it only exists on the server. */
    val pdfFile: String?,
    val syncState: LocalSyncState,
    /** `POST /documents` has succeeded at least once. */
    val remoteCreated: Boolean,
    /** `PUT /documents/:id/file` has succeeded. */
    val fileUploaded: Boolean,
    /** Title or tags were changed here after the last successful push. */
    val metadataDirty: Boolean,
    /** Server-side OCR status (`NONE`, `PENDING`, `DONE`, `FAILED`). */
    val ocrStatus: String,
    val hasSearchablePdf: Boolean,
    /** Error code of the last failed sync attempt; never a message. */
    val lastErrorCode: String?,
    val syncAttempts: Int,
)
