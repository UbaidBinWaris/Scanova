package app.scanova.core.data.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.documents.DocumentEditor
import app.scanova.core.data.documents.DocumentRepository
import app.scanova.core.data.documents.EditablePage
import app.scanova.core.data.storage.ExportFiles
import app.scanova.core.database.dao.DocumentDao
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Turns documents into files other apps can receive: PDFs, page images, long
 * images or text.
 *
 * Every file is written unencrypted into a private directory of its own under
 * the app's cache (see [ExportFiles]); that is the only place document content
 * exists in plaintext on disk. The caller hands the files to a share or copies
 * them to a place the user chose, then calls [discard] straight away; anything
 * left behind is removed at the next app start (`cleanUpLeftovers`).
 */
@Singleton
class DocumentExporter @Inject internal constructor(
    private val documents: DocumentRepository,
    private val editor: DocumentEditor,
    private val dao: DocumentDao,
    private val exports: ExportFiles,
    private val dispatchers: AppDispatchers,
) {

    /** What each of [documentIds] offers to an export, in the same order (duplicates dropped). */
    suspend fun describe(documentIds: List<String>): AppResult<List<ExportSource>> {
        val sources = mutableListOf<ExportSource>()
        for (id in documentIds.distinct()) {
            when (val source = describe(id)) {
                is AppResult.Success -> sources += source.value
                is AppResult.Failure -> return source
            }
        }
        return AppResult.Success(sources)
    }

    /** One document's title, pages and text. Fails with [AppError.NotFound] when it is gone. */
    suspend fun describe(documentId: String): AppResult<ExportSource> {
        val document = try {
            documents.observeDocument(documentId).first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ScanovaLog.warn(TAG, "describe_failed", e)
            return AppResult.Failure(AppError.LocalStorage)
        } ?: return AppResult.Failure(AppError.NotFound)

        val pages = when (val result = editor.pages(documentId)) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return result
        }
        val wholeText = try {
            dao.getById(documentId)?.ocrText.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ScanovaLog.warn(TAG, "text_read_failed", e)
            ""
        }
        return AppResult.Success(
            ExportSource(
                documentId = document.id,
                title = document.title,
                pageCount = if (pages.isNotEmpty()) pages.size else document.pageCount,
                pageSizes = pages.map { PixelSize(it.width, it.height) },
                pdfSizeBytes = document.sizeBytes,
                text = DocumentText(pages = pages.map { it.ocrText }, whole = wholeText),
            ),
        )
    }

    /**
     * Writes [sources] as [format]. [pageHeading] titles each page's text in a
     * text file (e.g. "Page 2"). [onProgress] reports finished steps (documents
     * or pages) out of a total, from a background thread.
     *
     * Cancelling removes everything written so far.
     */
    suspend fun export(
        sources: List<ExportSource>,
        format: ExportFormat,
        quality: ImageQuality,
        pageHeading: (pageNumber: Int) -> String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): AppResult<ExportBundle> = writeBundle(format.mimeType) { directory, names ->
        val total = when (format) {
            ExportFormat.PDF, ExportFormat.TEXT -> sources.size
            ExportFormat.IMAGES, ExportFormat.LONG_IMAGE -> sources.sumOf { it.pageCount }
        }
        val progress = Progress(total, onProgress)
        when (format) {
            ExportFormat.PDF -> sources.map { writePdf(it, directory, names, progress) }
            ExportFormat.IMAGES -> sources.flatMap { writeImages(it, quality, directory, names, progress) }
            ExportFormat.LONG_IMAGE -> sources.flatMap { writeLongImages(it, quality, directory, names, progress) }
            ExportFormat.TEXT -> sources.mapNotNull { writeText(it, pageHeading, directory, names, progress) }
        }
    }

    /** Writes [text] as one `.txt` file named after [title] (for texts too long to share inline). */
    suspend fun exportText(title: String, text: String): AppResult<ExportBundle> =
        writeBundle(ExportFormat.TEXT.mimeType) { directory, names ->
            val file = File(directory, names.next(ExportNames.text(title)))
            file.writeText(text, Charsets.UTF_8)
            listOf(file)
        }

    /** Deletes the files of [bundle]. Safe to call twice. */
    suspend fun discard(bundle: ExportBundle) {
        withContext(dispatchers.io) { exports.deleteDirectory(bundle.directory) }
    }

    // ── Writing ─────────────────────────────────────────────────────────

    private suspend fun writeBundle(
        mimeType: String,
        write: suspend (directory: File, names: ExportNames.Unique) -> List<File>,
    ): AppResult<ExportBundle> = withContext(dispatchers.io) {
        var directory: File? = null
        try {
            val created = exports.newDirectory().also { directory = it }
            val files = write(created, ExportNames.Unique())
            if (files.isEmpty()) {
                exports.deleteDirectory(created)
                AppResult.Failure(AppError.Unexpected("nothing_to_export"))
            } else {
                AppResult.Success(ExportBundle(created, files, mimeType))
            }
        } catch (e: CancellationException) {
            directory?.let(exports::deleteDirectory)
            throw e
        } catch (e: Throwable) {
            directory?.let(exports::deleteDirectory)
            ScanovaLog.warn(TAG, "export_failed", e)
            AppResult.Failure(e.toAppError())
        }
    }

    private suspend fun writePdf(source: ExportSource, directory: File, names: ExportNames.Unique, progress: Progress): File {
        val file = File(directory, names.next(ExportNames.pdf(source.title)))
        documents.readPdf(source.documentId) { input -> file.outputStream().use { input.copyTo(it) } }.orThrow()
        progress.step()
        return file
    }

    private suspend fun writeImages(
        source: ExportSource,
        quality: ImageQuality,
        directory: File,
        names: ExportNames.Unique,
        progress: Progress,
    ): List<File> = openPages(source, quality).use { pages ->
        (0 until pages.count).map { index ->
            val file = File(directory, names.next(ExportNames.page(source.title, index + 1, pages.count)))
            file.outputStream().buffered().use { pages.writeImage(index, it) }
            progress.step()
            file
        }
    }

    private suspend fun writeLongImages(
        source: ExportSource,
        quality: ImageQuality,
        directory: File,
        names: ExportNames.Unique,
        progress: Progress,
    ): List<File> = openPages(source, quality).use { pages ->
        drawLongImages(pages, source, quality, directory, names, progress)
    }

    private suspend fun drawLongImages(
        pages: PageSource,
        source: ExportSource,
        quality: ImageQuality,
        directory: File,
        names: ExportNames.Unique,
        progress: Progress,
    ): List<File> {
        // A phone short on memory gets shorter (and more) parts instead of a failure.
        var maxHeight = LongImageLayout.MAX_HEIGHT
        while (true) {
            val parts = LongImageLayout.plan(pages.sizes, maxWidth = quality.longImageWidth, maxHeight = maxHeight)
            val drafts = mutableListOf<File>()
            try {
                parts.forEachIndexed { index, part ->
                    val draft = File(directory, ".part-${source.documentId}-$index")
                    drafts += draft
                    drawLongImage(pages, part, quality, draft, progress)
                }
            } catch (e: OutOfMemoryError) {
                drafts.forEach(File::delete)
                if (maxHeight / 2 < MIN_LONG_IMAGE_HEIGHT) throw e
                maxHeight /= 2
                continue
            }
            // Named only once every part exists, so a retry never takes up names.
            return drafts.mapIndexed { index, draft ->
                val file = File(directory, names.next(ExportNames.longImage(source.title, index + 1, drafts.size)))
                if (!draft.renameTo(file)) throw IOException("rename_failed")
                file
            }
        }
    }

    private suspend fun drawLongImage(pages: PageSource, part: LongImagePart, quality: ImageQuality, target: File, progress: Progress) {
        val canvasBitmap = createBitmap(part.width, part.height)
        try {
            val canvas = Canvas(canvasBitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
            for (placement in part.placements) {
                val page = pages.bitmap(placement.pageIndex, PixelSize(placement.width, placement.height))
                try {
                    val destination = Rect(
                        placement.left,
                        placement.top,
                        placement.left + placement.width,
                        placement.top + placement.height,
                    )
                    canvas.drawBitmap(page, null, destination, paint)
                } finally {
                    page.recycle()
                }
                progress.step()
            }
            target.outputStream().buffered().use { ExportBitmaps.writeJpeg(canvasBitmap, quality.jpegQuality, it) }
        } finally {
            canvasBitmap.recycle()
        }
    }

    private fun writeText(
        source: ExportSource,
        pageHeading: (Int) -> String,
        directory: File,
        names: ExportNames.Unique,
        progress: Progress,
    ): File? {
        val text = source.text.toPlainText(pageHeading)
        progress.step()
        if (text.isEmpty()) return null
        val file = File(directory, names.next(ExportNames.text(source.title)))
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    // ── Page sources ────────────────────────────────────────────────────

    /** The pages of one document, from its stored images or drawn from its PDF. */
    private interface PageSource : Closeable {
        val count: Int

        /** Pixel size of each page at the export's resolution. */
        val sizes: List<PixelSize>

        /** Writes page [index] as a JPEG at the export's quality. */
        suspend fun writeImage(index: Int, output: OutputStream)

        /** Page [index] at roughly [size] or larger (the caller scales it to fit). Caller recycles it. */
        suspend fun bitmap(index: Int, size: PixelSize): Bitmap
    }

    private suspend fun openPages(source: ExportSource, quality: ImageQuality): PageSource {
        val stored = editor.pages(source.documentId).orThrow()
        if (stored.isNotEmpty()) return StoredPages(stored, quality)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw ExportFailure(AppError.Unexpected("pdf_render_unsupported"))
        val pdf = documents.readPdf(source.documentId) { it.readBytes() }.orThrow()
        val rendered = PdfPages.fromMemory(pdf)
        return try {
            RenderedPages(rendered, quality)
        } catch (e: Throwable) {
            rendered.close()
            throw e
        }
    }

    private inner class StoredPages(private val pages: List<EditablePage>, private val quality: ImageQuality) : PageSource {
        override val count: Int = pages.size
        override val sizes: List<PixelSize> = pages.map { PixelSize(it.width, it.height) }

        override suspend fun writeImage(index: Int, output: OutputStream) {
            val jpeg = editor.loadImage(pages[index].image).orThrow()
            val size = ExportBitmaps.bounds(jpeg) ?: throw IOException("page_unreadable")
            val maxEdge = quality.pageLongEdge
            if (maxEdge == null || size.longEdge <= maxEdge) {
                // Already a JPEG of the right size: shared exactly as stored, without a second compression.
                output.write(jpeg)
                return
            }
            val decoded = ExportBitmaps.decodeAtLeast(jpeg, maxEdge) ?: throw IOException("page_unreadable")
            val fitted = ExportBitmaps.fitWithin(decoded, maxEdge)
            try {
                ExportBitmaps.writeJpeg(fitted, quality.jpegQuality, output)
            } finally {
                fitted.recycle()
            }
        }

        override suspend fun bitmap(index: Int, size: PixelSize): Bitmap {
            val jpeg = editor.loadImage(pages[index].image).orThrow()
            return ExportBitmaps.decodeAtLeast(jpeg, size.longEdge) ?: throw IOException("page_unreadable")
        }

        override fun close() = Unit
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private class RenderedPages(private val pdf: PdfPages, private val quality: ImageQuality) : PageSource {
        override val count: Int = pdf.pageCount
        override val sizes: List<PixelSize> = (0 until count).map { index ->
            val points = pdf.pointSize(index)
            PdfRenderSizing.pixelSize(
                points.width,
                points.height,
                quality.renderDpi,
                quality.pageLongEdge ?: PdfRenderSizing.EXPORT_MAX_LONG_EDGE,
            )
        }

        override suspend fun writeImage(index: Int, output: OutputStream) {
            val bitmap = pdf.render(index, sizes[index])
            try {
                ExportBitmaps.writeJpeg(bitmap, quality.jpegQuality, output)
            } finally {
                bitmap.recycle()
            }
        }

        override suspend fun bitmap(index: Int, size: PixelSize): Bitmap = pdf.render(index, size)

        override fun close() = pdf.close()
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private class Progress(private val total: Int, private val report: (Int, Int) -> Unit) {
        private var done = 0

        fun step() {
            done++
            report(done.coerceAtMost(total), total)
        }
    }

    /** A failure with an [AppError] already decided; unwound to [writeBundle]. */
    private class ExportFailure(val error: AppError) : Exception(error.toString())

    private fun <T> AppResult<T>.orThrow(): T = when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> throw ExportFailure(error)
    }

    private fun Throwable.toAppError(): AppError = when (this) {
        is ExportFailure -> error
        is IOException, is GeneralSecurityException -> AppError.LocalStorage
        is OutOfMemoryError -> AppError.Unexpected("out_of_memory")
        else -> AppError.Unexpected(this::class.java.simpleName)
    }

    private companion object {
        const val TAG = "Export"

        /** Below this a long image is pointless; give up instead. */
        const val MIN_LONG_IMAGE_HEIGHT = 2_000
    }
}
