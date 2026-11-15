package app.scanova.core.data.sync

import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.model.SyncState
import app.scanova.core.data.util.truncateUtf8

/** The result of one sync run. [error] says why it stopped early, when it did. */
data class SyncReport(
    val outcome: SyncOutcome,
    val error: AppError? = null,
)

/** How a sync run ended. */
enum class SyncOutcome {
    /** Everything that could be done was done. Documents the server rejected are marked FAILED. */
    COMPLETE,

    /** Stopped early for a reason that will pass; run again later (with backoff). */
    RETRY_LATER,

    /** The session is not usable; nothing more happens until the user signs in. */
    AUTH_REQUIRED,
}

/**
 * Brings the phone and the server in line, in three steps:
 *
 * 1. **Deletes** — documents deleted here are deleted on the server.
 * 2. **Push** — new documents are created (`POST /documents`) and their PDF is
 *    uploaded (`PUT …/file`); edits to title and tags are sent (`PATCH`).
 * 3. **Pull** — changes made elsewhere are fetched with `updatedSince`,
 *    including deletions.
 *
 * Every step is idempotent. Creating a document twice is harmless because the
 * id is generated on the phone; uploading the same file twice is a no-op on
 * the server; a run that dies half-way simply continues on the next run, using
 * the `remoteCreated` / `fileUploaded` flags to skip what is already done.
 */
internal class SyncEngine(
    private val store: SyncStore,
    private val api: SyncApi,
    private val files: SyncFiles,
) {
    suspend fun run(): SyncReport {
        pushDeletes()?.let { return it }
        pushChanges()?.let { return it }
        return pull()
    }

    private fun stop(error: AppError): SyncReport = SyncReport(
        outcome = if (error.failureKind() == FailureKind.AUTH) SyncOutcome.AUTH_REQUIRED else SyncOutcome.RETRY_LATER,
        error = error,
    )

    // ── 1. Deletes ──────────────────────────────────────────────────────

    private suspend fun pushDeletes(): SyncReport? {
        for (document in store.pendingDelete()) {
            if (!document.remoteCreated) {
                store.remove(document.id)
                continue
            }
            when (val result = api.delete(document.id)) {
                is AppResult.Success -> store.remove(document.id)
                is AppResult.Failure -> {
                    val error = result.error
                    when {
                        // Already gone on the server: that is the outcome we wanted.
                        error.isNotFound() -> store.remove(document.id)
                        error.failureKind() != FailureKind.PERMANENT -> return stop(error)
                        // A delete the server refuses for good is kept and tried again on a later run.
                        else -> Unit
                    }
                }
            }
        }
        return null
    }

    // ── 2. Push ─────────────────────────────────────────────────────────

    private suspend fun pushChanges(): SyncReport? {
        for (document in store.pendingPush()) {
            val outcome = when (document.state) {
                SyncState.LOCAL_ONLY, SyncState.UPLOADING -> upload(document)
                // A rejected document is only tried again when the user asks (RETRY_REQUESTED).
                SyncState.FAILED -> null
                SyncState.SYNCED -> if (document.metadataDirty) pushMetadata(document) else null
                SyncState.PENDING_DELETE -> null
            }
            if (outcome != null) return outcome
        }
        return null
    }

    /** Returns null to continue with the next document, or the outcome that ends the run. */
    private suspend fun upload(snapshot: SyncDocument): SyncReport? {
        val id = snapshot.id
        if (!store.transition(id, SyncEvent.UPLOAD_STARTED)) return null

        if (!snapshot.remoteCreated) {
            val clientOcrText = snapshot.ocrText.takeIf { it.isNotBlank() }?.let { truncateUtf8(it, MAX_CLIENT_OCR_BYTES) }
            when (val created = api.create(snapshot, clientOcrText)) {
                is AppResult.Failure -> return failUpload(id, created.error)
                is AppResult.Success -> store.updateProgress(id) { progress ->
                    progress.copy(
                        remoteCreated = true,
                        // The request carried the snapshot's title and tags. If they were
                        // edited meanwhile, the edit is still waiting to be sent.
                        metadataDirty = progress.title != snapshot.title || progress.tags != snapshot.tags,
                    )
                }
            }
        }

        if (!snapshot.fileUploaded) {
            val pdfFile = snapshot.pdfFile
            val sha256 = snapshot.contentSha256
            val size = snapshot.sizeBytes
            if (pdfFile == null || sha256 == null || size == null || !files.exists(pdfFile)) {
                return failUpload(id, AppError.LocalStorage)
            }
            when (val uploaded = api.upload(id, sha256, size) { files.open(pdfFile) }) {
                is AppResult.Failure -> return failUpload(id, uploaded.error)
                is AppResult.Success -> store.updateProgress(id) { progress ->
                    progress.copy(
                        fileUploaded = true,
                        ocrStatus = uploaded.value.ocrStatus,
                        hasSearchablePdf = uploaded.value.hasSearchablePdf,
                    )
                }
            }
        }

        val current = store.get(id) ?: return null
        if (current.metadataDirty) {
            when (val patched = api.updateMetadata(id, current.title, current.tags)) {
                is AppResult.Failure -> return failUpload(id, patched.error)
                is AppResult.Success -> store.updateProgress(id) { progress ->
                    progress.copy(metadataDirty = progress.title != current.title || progress.tags != current.tags)
                }
            }
        }

        store.transition(id, SyncEvent.UPLOAD_SUCCEEDED) { it.copy(lastErrorCode = null, syncAttempts = 0) }
        return null
    }

    private suspend fun failUpload(id: String, error: AppError): SyncReport? = when (error.failureKind()) {
        FailureKind.AUTH -> {
            store.transition(id, SyncEvent.UPLOAD_DEFERRED)
            stop(error)
        }
        FailureKind.TRANSIENT -> {
            store.transition(id, SyncEvent.UPLOAD_DEFERRED) { it.copy(syncAttempts = it.syncAttempts + 1) }
            stop(error)
        }
        FailureKind.PERMANENT -> {
            store.transition(id, SyncEvent.UPLOAD_REJECTED) {
                it.copy(lastErrorCode = SyncErrorCodec.encode(error), syncAttempts = it.syncAttempts + 1)
            }
            // One rejected document must not hold up the others.
            null
        }
    }

    private suspend fun pushMetadata(snapshot: SyncDocument): SyncReport? {
        when (val patched = api.updateMetadata(snapshot.id, snapshot.title, snapshot.tags)) {
            is AppResult.Success -> store.updateProgress(snapshot.id) { progress ->
                progress.copy(metadataDirty = progress.title != snapshot.title || progress.tags != snapshot.tags)
            }
            is AppResult.Failure -> {
                val error = patched.error
                when {
                    // Deleted from another device while it was being edited here.
                    error.isNotFound() -> store.remove(snapshot.id)
                    error.failureKind() != FailureKind.PERMANENT -> return stop(error)
                    // The server will never accept this edit; drop it so it is not sent forever.
                    // The next pull restores the server's title and tags.
                    else -> store.updateProgress(snapshot.id) { it.copy(metadataDirty = false) }
                }
            }
        }
        return null
    }

    // ── 3. Pull ─────────────────────────────────────────────────────────

    private suspend fun pull(): SyncReport {
        val since = store.pullCursor()
        var newest = since
        var cursor: String? = null
        var pages = 0
        do {
            val page = when (val result = api.list(cursor, since)) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> return stop(result.error)
            }
            for (remote in page.documents) {
                applyRemote(remote)
                // ISO-8601 UTC timestamps of equal precision sort as text.
                if (newest == null || remote.updatedAtIso > newest) newest = remote.updatedAtIso
            }
            cursor = page.nextCursor
            pages++
        } while (cursor != null && pages < MAX_PAGES_PER_RUN)

        // The cursor only moves once every page was applied, so a failed run repeats
        // the whole window instead of skipping part of it.
        if (cursor == null && newest != null && newest != since) store.setPullCursor(newest)
        return SyncReport(if (cursor == null) SyncOutcome.COMPLETE else SyncOutcome.RETRY_LATER)
    }

    private suspend fun applyRemote(remote: RemoteDocument) {
        val local = store.get(remote.id)
        when {
            remote.deleted -> if (local != null && local.remoteCreated) store.remove(remote.id)
            local == null -> {
                // A document another device is still uploading has no file yet;
                // it shows up here once it is READY (its updatedAt changes then).
                if (remote.ready) {
                    store.applyRemote(remote)
                    fetchServerText(remote)
                }
            }
            // Local work in progress wins until it has been pushed.
            local.state != SyncState.SYNCED -> Unit
            else -> {
                store.applyRemote(remote)
                if (local.ocrText.isBlank()) fetchServerText(remote)
            }
        }
    }

    /** Best effort: text recognised by the server makes documents from other devices searchable here. */
    private suspend fun fetchServerText(remote: RemoteDocument) {
        if (remote.ocrStatus != OCR_DONE) return
        val text = api.text(remote.id)
        if (text is AppResult.Success && text.value.isNotBlank()) store.setOcrText(remote.id, text.value)
    }

    private fun AppError.isNotFound(): Boolean = this is AppError.Api && code == ApiErrorCode.NOT_FOUND

    private companion object {
        /** `clientOcrText` limit from the API contract (1 MiB). */
        const val MAX_CLIENT_OCR_BYTES = 1024 * 1024
        const val MAX_PAGES_PER_RUN = 200
        const val OCR_DONE = "DONE"
    }
}
