package app.scanova.core.database.entity

import androidx.room3.Entity
import androidx.room3.Fts4
import androidx.room3.FtsOptions

/**
 * Full-text index over the searchable columns of [DocumentEntity]. It is an
 * external-content FTS4 table: Room keeps it in step with `documents` through
 * triggers, and it stores only the index, not a second copy of the text. Like
 * every other table it sits inside the SQLCipher-encrypted database file.
 */
@Fts4(contentEntity = DocumentEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "documents_fts")
data class DocumentFtsEntity(
    val title: String,
    val tags: String,
    val ocrText: String,
)
