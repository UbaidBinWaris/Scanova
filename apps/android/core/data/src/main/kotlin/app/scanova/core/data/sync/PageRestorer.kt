package app.scanova.core.data.sync

import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.common.util.Sha256CountingOutputStream
import app.scanova.core.data.pdf.JpegInfo
import app.scanova.core.data.storage.FileNames
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How much one sync run may spend on restoring pages. */
internal data class RestoreLimits(
    val maxMillis: Long = 4 * 60_000L,
    val maxBytes: Long = 256L * 1024 * 1024,
)

/** How a restore pass ended. */
internal sealed interface RestoreResult {
    /** Nothing left to restore. */
    data object Done : RestoreResult

    /** The run's time or download budget is spent; the next run goes on. */
    data object MoreLeft : RestoreResult

    /** Stopped by an error that will pass (offline, server busy) or a lost session. */
    data class Stopped(val error: AppError) : RestoreResult
}

/**
 * Makes documents that came from the server complete on this phone, in the
 * background: page images, recipes and originals, so thumbnails, "share as
 * images" and lossless re-editing work as for documents scanned here.
 *
 * For each document whose pages are missing or stale (restored from the
 * account, or changed on another device):
 *
 * 1. the server's page list (`GET …/pages`): ids, order, recipes, which pages have an original;
 * 2. the PDF as its phone wrote it (`variant=original`), checked against its SHA-256;
 * 3. each page's current image taken out of that PDF ([sources], first that works);
 * 4. the page rows replace what was there, in one transaction, only if the
 *    document did not change meanwhile.
 *
 * Originals are downloaded afterwards, page by page, each recorded as soon as
 * it is stored, so a long restore spreads over several runs without repeating work.
 *
 * Everything is best effort and bounded by [limits]: a failure never touches
 * the document itself, and anything not finished is picked up by a later run.
 * A server without page backup (NOT_FOUND) still gets page images from the PDF.
 */
internal class PageRestorer(
    private val store: PageStore,
    private val api: SyncApi,
    private val files: SyncFiles,
    private val sources: List<PdfPageImages>,
    private val limits: RestoreLimits = RestoreLimits(),
    /** Monotonic milliseconds, for the time budget. */
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val io: CoroutineContext = Dispatchers.IO,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun run(): RestoreResult {
        val budget = Budget(limits, elapsedMillis)
        store.queueDocumentsWithoutPages()
        for (document in store.pagesToRestore()) {
            if (budget.spent()) return RestoreResult.MoreLeft
            when (val outcome = restore(document, budget)) {
                Outcome.Restored, Outcome.Later -> Unit
                Outcome.GiveUp -> store.stopRestoring(document.id)
                is Outcome.Stop -> return RestoreResult.Stopped(outcome.error)
            }
        }
        for (page in store.missingOriginals()) {
            if (budget.spent()) return RestoreResult.MoreLeft
            fetchOriginal(page, budget)?.let { return RestoreResult.Stopped(it) }
        }
        return RestoreResult.Done
    }

    // ── One document ────────────────────────────────────────────────────

    private suspend fun restore(document: SyncDocument, budget: Budget): Outcome {
        val remote = when (val listed = api.pages(document.id)) {
            is AppResult.Success -> listed.value
            // A server without page backup: pages are rebuilt from the PDF alone.
            is AppResult.Failure -> {
                val error = listed.error
                if (error.meansNoPageBackup() || error.failureKind() == FailureKind.PERMANENT) null else return Outcome.Stop(error)
            }
        }
        val created = mutableListOf<String>()
        try {
            val localPdf = document.pdfFile?.takeIf { exists(it) }
            var images = localPdf?.let { extract(it, created) }
            var downloadedPdf: String? = null
            if (images == null) {
                downloadedPdf = when (val pdf = downloadPdf(document, budget, created)) {
                    is Download.Stored -> pdf.name
                    is Download.Failed -> return pdf.outcome
                }
                images = extract(downloadedPdf, created) ?: return Outcome.GiveUp
            }
            // The PDF just downloaded becomes the document's file, unless the server has a
            // searchable copy (with its text layer): that one is fetched when the document is opened.
            val newPdf = downloadedPdf?.takeIf { localPdf == null && !document.hasSearchablePdf }
            val rows = rows(document, images, remote, store.pages(document.id))
            val unused = store.restorePages(
                PageRestore(
                    documentId = document.id,
                    contentSha256 = document.contentSha256,
                    pages = rows,
                    pdfFile = newPdf,
                    remotePagesUpdatedAt = remote?.pagesUpdatedAtIso,
                ),
            ) ?: return Outcome.Later // It changed meanwhile; the next run looks again.
            val kept = rows.flatMap { listOfNotNull(it.imageFile, it.originalFile) }.toSet() + listOfNotNull(newPdf)
            created.removeAll(kept)
            withContext(io) { unused.filter { it !in kept }.forEach(::deleteQuietly) }
            return Outcome.Restored
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ScanovaLog.warn(TAG, "page_restore_io_failed", e)
            return Outcome.Later
        } catch (e: GeneralSecurityException) {
            ScanovaLog.warn(TAG, "page_restore_crypto_failed", e)
            return Outcome.Later
        } finally {
            // Whatever this attempt stored but did not hand over to the document.
            withContext(io) { created.forEach(::deleteQuietly) }
        }
    }

    private suspend fun downloadPdf(document: SyncDocument, budget: Budget, created: MutableList<String>): Download {
        val name = FileNames.pdfRevision(document.id)
        created += name
        val result = api.downloadPdf(document.id) { input ->
            withContext(io) {
                files.write(name) { output ->
                    val counting = Sha256CountingOutputStream(output)
                    input.copyTo(counting)
                    counting.flush()
                    counting.sha256Hex() to counting.byteCount
                }
            }
        }
        return when (result) {
            is AppResult.Failure -> Download.Failed(
                if (result.error.failureKind() == FailureKind.PERMANENT) Outcome.GiveUp else Outcome.Stop(result.error),
            )
            is AppResult.Success -> {
                val (sha256, size) = result.value
                budget.downloaded(size)
                // Another device replaced the file after the pull saw it: the next pull brings the new hash.
                if (document.contentSha256 != null && sha256 != document.contentSha256) {
                    Download.Failed(Outcome.Later)
                } else {
                    Download.Stored(name)
                }
            }
        }
    }

    /** The page images of PDF [pdf], stored as new files; null when no source can read it. */
    private suspend fun extract(pdf: String, created: MutableList<String>): List<PageImage>? = withContext(io) {
        for (source in sources) {
            val images = mutableListOf<PageImage>()
            val count = try {
                source.pages({ files.open(pdf) }) { _, jpeg ->
                    val info = JpegInfo.read(jpeg) ?: throw IOException("not_a_jpeg")
                    val name = FileNames.pageRevision(newId())
                    created += name
                    files.write(name) { it.write(jpeg) }
                    images += PageImage(name, info.width, info.height)
                }
            } catch (e: IOException) {
                ScanovaLog.warn(TAG, "page_images_failed", e)
                null
            } catch (e: RuntimeException) {
                // A file this source cannot parse is not a reason to stop the sync.
                ScanovaLog.warn(TAG, "page_images_unreadable", e)
                null
            }
            if (count != null && count == images.size && count > 0) return@withContext images
            images.forEach { deleteQuietly(it.file); created.remove(it.file) }
        }
        null
    }

    /**
     * The new page rows. With a server page list that matches the PDF page for
     * page, pages take its ids, recipes and originals (an original already on
     * this phone with the same hash is kept, others are downloaded later).
     * Without one (a server without page backup, or a list that lags behind the
     * PDF), pages keep this phone's ids and originals by position when the
     * count still matches; their recipes are unknown for the new images.
     */
    private suspend fun rows(
        document: SyncDocument,
        images: List<PageImage>,
        remote: RemotePageList?,
        local: List<SyncPage>,
    ): List<SyncPage> {
        val server = remote?.pages?.takeIf { it.size == images.size }
        val localById = local.associateBy { it.id }
        val localByPosition = local.takeIf { it.size == images.size }
        return images.mapIndexed { index, image ->
            val info = server?.get(index)
            // The page this phone already had in this place: by id when the server names it, else by position.
            val previous = if (info != null) localById[info.id] else localByPosition?.get(index)
            val mine = previous?.takeIf { page -> page.originalFile != null && exists(page.originalFile) }
            val base = SyncPage(
                id = info?.id ?: previous?.id ?: newId(),
                documentId = document.id,
                position = index,
                imageFile = image.file,
                width = image.width,
                height = image.height,
                ocrText = previous?.ocrText.orEmpty(),
                editJson = info?.editJson,
                originalFile = null,
                originalSha256 = null,
                originalSizeBytes = null,
                originalUploaded = false,
            )
            when {
                // The server has this original: keep the same one if it is here, else fetch it later.
                info?.originalSha256 != null -> base.copy(
                    originalFile = mine?.takeIf { it.originalSha256 == info.originalSha256 }?.originalFile,
                    originalSha256 = info.originalSha256,
                    originalSizeBytes = info.originalSizeBytes,
                    originalUploaded = true,
                )
                // Only this phone has it.
                mine != null -> base.copy(
                    originalFile = mine.originalFile,
                    originalSha256 = mine.originalSha256,
                    originalSizeBytes = mine.originalSizeBytes,
                    originalUploaded = mine.originalUploaded && info == null,
                )
                else -> base
            }
        }
    }

    // ── Originals ───────────────────────────────────────────────────────

    /** Downloads one missing original. Returns the error that should end the run, or null. */
    private suspend fun fetchOriginal(page: SyncPage, budget: Budget): AppError? {
        val expected = page.originalSha256 ?: return null
        val name = withContext(io) {
            FileNames.original(page.id).takeUnless(files::exists) ?: "${FileNames.ORIGINALS_DIR}/${page.id}-${newId()}.jpg"
        }
        var attached = false
        try {
            val result = api.downloadOriginal(page.documentId, page.id) { input ->
                withContext(io) {
                    files.write(name) { output ->
                        val counting = Sha256CountingOutputStream(output)
                        input.copyTo(counting)
                        counting.flush()
                        counting.sha256Hex() to counting.byteCount
                    }
                }
            }
            when (result) {
                is AppResult.Success -> {
                    val (sha256, size) = result.value
                    budget.downloaded(size)
                    // A different hash means the page changed meanwhile; the next pull corrects the row.
                    attached = sha256 == expected && store.attachOriginal(page.id, expected, name)
                }
                is AppResult.Failure -> when {
                    result.error.failureKind() != FailureKind.PERMANENT -> return result.error
                    // The server no longer has it: stop asking for it.
                    else -> store.updatePage(page.id) {
                        it.copy(originalUploaded = false, originalSha256 = null, originalSizeBytes = null)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ScanovaLog.warn(TAG, "original_download_io_failed", e)
        } catch (e: GeneralSecurityException) {
            ScanovaLog.warn(TAG, "original_download_crypto_failed", e)
        } finally {
            if (!attached) withContext(io) { deleteQuietly(name) }
        }
        return null
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private suspend fun exists(name: String): Boolean = withContext(io) { files.exists(name) }

    private fun deleteQuietly(name: String) {
        try {
            files.delete(name)
        } catch (e: IOException) {
            ScanovaLog.warn(TAG, "restore_cleanup_failed", e)
        }
    }

    private class PageImage(val file: String, val width: Int, val height: Int)

    private sealed interface Download {
        data class Stored(val name: String) : Download

        data class Failed(val outcome: Outcome) : Download
    }

    private sealed interface Outcome {
        data object Restored : Outcome

        /** Not now (changed meanwhile, local trouble); stays queued for a later run. */
        data object Later : Outcome

        /** This document cannot get pages on this phone; stop trying until it changes on the server. */
        data object GiveUp : Outcome

        data class Stop(val error: AppError) : Outcome
    }

    private class Budget(private val limits: RestoreLimits, private val elapsedMillis: () -> Long) {
        private val startedAt = elapsedMillis()
        private var bytes = 0L

        fun downloaded(count: Long) {
            bytes += count
        }

        fun spent(): Boolean = bytes >= limits.maxBytes || elapsedMillis() - startedAt >= limits.maxMillis
    }

    private companion object {
        const val TAG = "PageRestore"
    }
}
