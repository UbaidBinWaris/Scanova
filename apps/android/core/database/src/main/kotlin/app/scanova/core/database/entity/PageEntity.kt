package app.scanova.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * One page of a document. A page keeps two images:
 *
 * - the **original**: the untouched camera photo (or imported picture), so the
 *   page can be cropped, rotated and filtered again at any time;
 * - the **current** image ([imageFile]): the original with [editJson] applied.
 *   This is what the PDF, the thumbnails and "share as images" use.
 *
 * Pages scanned before originals were kept, and pages restored from a backup
 * that has no original, have [originalFile] = null and can only be edited
 * starting from their current image.
 */
@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId")],
)
data class PageEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    /** Zero-based order within the document. */
    val position: Int,
    /** Name of the encrypted current image (JPEG) in the file store. */
    val imageFile: String,
    /** Size of the current image in pixels. */
    val width: Int,
    val height: Int,
    val ocrText: String,
    /** Name of the encrypted original (JPEG) in the file store, when there is one. */
    @ColumnInfo(defaultValue = "NULL")
    val originalFile: String? = null,
    /** SHA-256 (lower-case hex) and size of the original's plaintext, for an idempotent upload. */
    @ColumnInfo(defaultValue = "NULL")
    val originalSha256: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val originalSizeBytes: Long? = null,
    /** The original is stored on the server (backup). */
    @ColumnInfo(defaultValue = "0")
    val originalUploaded: Boolean = false,
    /**
     * How the current image was made from the original (crop, rotation, filter,
     * adjustments, markup), as JSON: `app.scanova.core.data.model.PageEdit`.
     * Null when unknown (pages made before edit recipes were recorded).
     */
    @ColumnInfo(defaultValue = "NULL")
    val editJson: String? = null,
)
