package app.scanova.core.data.documents

import app.scanova.core.data.model.Document
import app.scanova.core.data.model.DocumentPage
import app.scanova.core.data.model.SyncState
import app.scanova.core.data.sync.SyncErrorCodec
import app.scanova.core.database.entity.DocumentEntity
import app.scanova.core.database.entity.LocalSyncState
import app.scanova.core.database.entity.PageEntity
import java.time.Instant

private const val TAG_SEPARATOR = "\n"

internal fun encodeTags(tags: List<String>): String = tags.joinToString(TAG_SEPARATOR)

internal fun decodeTags(stored: String): List<String> =
    if (stored.isEmpty()) emptyList() else stored.split(TAG_SEPARATOR)

internal fun LocalSyncState.toDomain(): SyncState = SyncState.valueOf(name)

internal fun SyncState.toLocal(): LocalSyncState = LocalSyncState.valueOf(name)

internal fun DocumentEntity.toDomain(): Document = Document(
    id = id,
    title = title,
    tags = decodeTags(tags),
    pageCount = pageCount,
    sizeBytes = sizeBytes,
    capturedAt = Instant.ofEpochMilli(capturedAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    syncState = syncState.toDomain(),
    hasUnsyncedChanges = metadataDirty,
    syncError = if (syncState == LocalSyncState.FAILED) SyncErrorCodec.decode(lastErrorCode) else null,
    storedOnDevice = pdfFile != null,
)

internal fun PageEntity.toDomain(): DocumentPage = DocumentPage(
    id = id,
    position = position,
    width = width,
    height = height,
)
