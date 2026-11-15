package app.scanova.core.data.sync

import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.common.util.Sha256CountingOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.GeneralSecurityException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Backs up every page of a synced document: the page list with each page's
 * recipe (`PUT …/pages`), then each original photo the server does not have
 * yet (`PUT …/pages/:pageId/original`). Runs after the document's PDF is on the
 * server, so the PDF always stays the current version.
 *
 * Like the rest of sync it is idempotent and resumable: an original is marked
 * uploaded as soon as the server has it, and pagesDirty is only cleared once
 * the list and every original that can be sent are there.
 *
 * A server without page backup answers the page routes with NOT_FOUND. That is
 * never an error of the document: page backup simply waits (pagesDirty stays
 * set) until the server is updated.
 */
internal class PagePusher(
    private val store: PageStore,
    private val api: SyncApi,
    private val files: SyncFiles,
    private val io: CoroutineContext = Dispatchers.IO,
) {
    /** Returns null to let the run go on, or the error that ends it (offline, signed out, server trouble). */
    suspend fun push(): AppError? {
        var originalsAllowed = true
        for (document in store.pagesToPush()) {
            when (val outcome = pushDocument(document.id, originalsAllowed)) {
                Outcome.Sent, Outcome.NotYet -> Unit
                // The server has no page backup yet: nothing else will work in this run either.
                Outcome.Unsupported -> return null
                // The account is full: page lists still go, originals wait for space.
                Outcome.QuotaFull -> originalsAllowed = false
                is Outcome.Stop -> return outcome.error
            }
        }
        return null
    }

    private suspend fun pushDocument(documentId: String, originalsAllowed: Boolean): Outcome {
        val pages = store.pages(documentId)
        if (pages.isEmpty()) {
            store.pagesPushed(documentId, null)
            return Outcome.Sent
        }
        val listed = when (val result = api.replacePages(documentId, pages.map { it.outgoing() })) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return listFailed(documentId, result.error)
        }
        var newest = listed.pagesUpdatedAtIso
        var complete = true
        for (page in pages) {
            if (page.originalUploaded) continue
            val file = page.originalFile ?: continue
            // An original lost on this phone can never be sent; it does not hold the rest up.
            if (!withContext(io) { files.exists(file) }) continue
            if (!originalsAllowed) {
                complete = false
                continue
            }
            // An original that cannot be read here (damaged file) is left out, like a lost one.
            val (sha256, size) = describe(page, file) ?: continue
            when (val uploaded = api.uploadOriginal(documentId, page.id, sha256, size) { files.open(file) }) {
                is AppResult.Success -> {
                    store.updatePage(page.id) { it.copy(originalUploaded = true) }
                    newest = later(newest, uploaded.value.updatedAtIso)
                }
                is AppResult.Failure -> {
                    val error = uploaded.error
                    when {
                        // Not the API answering (a proxy refusing the body, a server mid-upgrade): try again next run.
                        error == AppError.UnexpectedResponse -> return Outcome.NotYet
                        error.failureKind() != FailureKind.PERMANENT -> return Outcome.Stop(error)
                        error.isCode(ApiErrorCode.QUOTA_EXCEEDED) -> return Outcome.QuotaFull
                        // The server's page list changed meanwhile (or it has no page backup): send it all again next run.
                        error.isCode(ApiErrorCode.NOT_FOUND) -> return Outcome.NotYet
                        error.isCode(ApiErrorCode.CHECKSUM_MISMATCH) -> {
                            // The recorded hash is wrong; it is computed again from the file next time.
                            store.updatePage(page.id) { it.copy(originalSha256 = null, originalSizeBytes = null) }
                            complete = false
                        }
                        // Refused for good (too large, not a JPEG): this original stays on the phone only.
                        else -> ScanovaLog.warn(TAG, "original_refused code=${SyncErrorCodec.encode(error)}")
                    }
                }
            }
        }
        if (!complete) return Outcome.NotYet
        store.pagesPushed(documentId, newest)
        return Outcome.Sent
    }

    private suspend fun listFailed(documentId: String, error: AppError): Outcome = when {
        error.meansNoPageBackup() -> Outcome.Unsupported
        error.failureKind() != FailureKind.PERMANENT -> Outcome.Stop(error)
        else -> {
            // The server will never take this list (it breaks a limit). Sending it again
            // cannot help; the next edit of the document tries again.
            ScanovaLog.warn(TAG, "page_list_refused code=${SyncErrorCodec.encode(error)}")
            store.pagesPushed(documentId, null)
            Outcome.Sent
        }
    }

    /** SHA-256 and size of an original, from its row or (once) from the file itself; null if it cannot be read. */
    private suspend fun describe(page: SyncPage, file: String): Pair<String, Long>? {
        val sha = page.originalSha256
        val size = page.originalSizeBytes
        if (sha != null && size != null) return sha to size
        val (computedSha, computedSize) = try {
            withContext(io) {
                files.open(file).use { input ->
                    val counting = Sha256CountingOutputStream(Discard)
                    input.copyTo(counting)
                    counting.sha256Hex() to counting.byteCount
                }
            }
        } catch (e: IOException) {
            ScanovaLog.warn(TAG, "original_unreadable", e)
            return null
        } catch (e: GeneralSecurityException) {
            ScanovaLog.warn(TAG, "original_unreadable", e)
            return null
        }
        store.updatePage(page.id) { it.copy(originalSha256 = computedSha, originalSizeBytes = computedSize) }
        return computedSha to computedSize
    }

    private fun SyncPage.outgoing() = OutgoingPage(id, position, width, height, editJson)

    private sealed interface Outcome {
        /** List and originals are on the server. */
        data object Sent : Outcome

        /** Not finished; pagesDirty stays set and the next run continues. */
        data object NotYet : Outcome

        data object Unsupported : Outcome

        data object QuotaFull : Outcome

        data class Stop(val error: AppError) : Outcome
    }

    /** Counts and hashes without storing (`OutputStream.nullOutputStream()` needs API 33). */
    private object Discard : OutputStream() {
        override fun write(b: Int) = Unit

        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private companion object {
        const val TAG = "PageSync"
    }
}

internal fun AppError.isCode(code: ApiErrorCode): Boolean = this is AppError.Api && this.code == code

/**
 * How a server from before page backup answers the page routes: 404 (unknown
 * route; the API also maps 405 to NOT_FOUND), or something that is not the API
 * at all. Page sync then waits; it never fails or blocks the document.
 */
internal fun AppError.meansNoPageBackup(): Boolean =
    this == AppError.UnexpectedResponse ||
        (this is AppError.Api && (code == ApiErrorCode.NOT_FOUND || httpStatus == 404 || httpStatus == 405))

/** The later of two server timestamps (fixed-width ISO-8601 UTC, so they compare as text). */
internal fun later(a: String?, b: String?): String? = when {
    a == null -> b
    b == null -> a
    else -> maxOf(a, b)
}
