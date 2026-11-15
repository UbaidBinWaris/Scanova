package app.scanova.core.database.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** One page image of a document scanned on this phone. Deleted with its document. */
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
    /** Name of the encrypted JPEG in the file store. */
    val imageFile: String,
    val width: Int,
    val height: Int,
    val ocrText: String,
)
