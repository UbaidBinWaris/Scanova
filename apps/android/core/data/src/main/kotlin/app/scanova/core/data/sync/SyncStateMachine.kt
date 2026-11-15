package app.scanova.core.data.sync

import app.scanova.core.data.model.SyncState

/** Something that happens to a document's synchronisation. */
enum class SyncEvent {
    /** The sync engine picked the document up and is about to send it. */
    UPLOAD_STARTED,

    /** Document and file are both on the server. */
    UPLOAD_SUCCEEDED,

    /** The attempt could not finish for a reason that will pass (offline, server busy, signed out). */
    UPLOAD_DEFERRED,

    /** The server refused this document, or its local file is unusable. Waiting will not help. */
    UPLOAD_REJECTED,

    /** The user asked to try a failed document again. */
    RETRY_REQUESTED,

    /** The user deleted the document on this phone. */
    DELETE_REQUESTED,

    /** The pages changed here (filter, crop, order, pages added or removed): the new PDF must be sent. */
    CONTENT_CHANGED,
}

/**
 * The only place that decides how a document's [SyncState] may change.
 *
 * ```
 *                 UPLOAD_STARTED            UPLOAD_SUCCEEDED
 *   LOCAL_ONLY ─────────────────► UPLOADING ────────────────► SYNCED
 *       ▲   ▲                       │   │
 *       │   └── UPLOAD_DEFERRED ────┘   │ UPLOAD_REJECTED
 *       │                               ▼
 *       └────── RETRY_REQUESTED ───── FAILED
 *
 *   any state except PENDING_DELETE ── DELETE_REQUESTED ──► PENDING_DELETE
 *   any state except PENDING_DELETE ── CONTENT_CHANGED ───► LOCAL_ONLY
 * ```
 *
 * `PENDING_DELETE` has no way out: once the user deletes a document, a late
 * result from an upload that was already running cannot bring it back.
 *
 * `CONTENT_CHANGED` sends an edited document back to the queue. Edits are
 * committed while no sync runs (`SyncRunner.exclusive`), so an upload of the
 * previous version can never mark the new one as sent.
 */
object SyncStateMachine {

    /** The state after [event], or null when [event] is not allowed in [state]. */
    fun next(state: SyncState, event: SyncEvent): SyncState? = when (state) {
        SyncState.LOCAL_ONLY -> when (event) {
            SyncEvent.UPLOAD_STARTED -> SyncState.UPLOADING
            SyncEvent.DELETE_REQUESTED -> SyncState.PENDING_DELETE
            SyncEvent.CONTENT_CHANGED -> SyncState.LOCAL_ONLY
            else -> null
        }
        SyncState.UPLOADING -> when (event) {
            // A run that died mid-upload leaves UPLOADING behind; the next run resumes it.
            SyncEvent.UPLOAD_STARTED -> SyncState.UPLOADING
            SyncEvent.UPLOAD_SUCCEEDED -> SyncState.SYNCED
            SyncEvent.UPLOAD_DEFERRED -> SyncState.LOCAL_ONLY
            SyncEvent.UPLOAD_REJECTED -> SyncState.FAILED
            SyncEvent.DELETE_REQUESTED -> SyncState.PENDING_DELETE
            SyncEvent.CONTENT_CHANGED -> SyncState.LOCAL_ONLY
            SyncEvent.RETRY_REQUESTED -> null
        }
        SyncState.SYNCED -> when (event) {
            SyncEvent.DELETE_REQUESTED -> SyncState.PENDING_DELETE
            SyncEvent.CONTENT_CHANGED -> SyncState.LOCAL_ONLY
            else -> null
        }
        SyncState.FAILED -> when (event) {
            SyncEvent.RETRY_REQUESTED -> SyncState.LOCAL_ONLY
            SyncEvent.DELETE_REQUESTED -> SyncState.PENDING_DELETE
            // New content may be exactly what the server refused before.
            SyncEvent.CONTENT_CHANGED -> SyncState.LOCAL_ONLY
            else -> null
        }
        SyncState.PENDING_DELETE -> null
    }
}
