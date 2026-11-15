package app.scanova.core.database.entity

/**
 * Where a document stands between this phone and the server
 * (docs/ARCHITECTURE.md §9). Stored by name, so the names are part of the schema.
 */
enum class LocalSyncState {
    /** Exists on this phone only; not yet sent. */
    LOCAL_ONLY,

    /** The sync worker is sending it right now. */
    UPLOADING,

    /** The server has the document and its file. */
    SYNCED,

    /** The last attempt failed; see the stored error code. */
    FAILED,

    /** Deleted here, deletion not yet confirmed by the server. Hidden from the library. */
    PENDING_DELETE,
}
