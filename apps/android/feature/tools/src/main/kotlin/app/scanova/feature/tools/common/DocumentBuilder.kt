package app.scanova.feature.tools.common

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.LruCache
import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.result.AppResult
import app.scanova.core.common.util.TimeSource
import app.scanova.core.data.documents.DocumentEditor
import app.scanova.core.data.documents.DocumentRepository
import app.scanova.core.data.documents.PageDraft
import app.scanova.core.data.export.ExportBitmaps
import app.scanova.core.data.export.PdfPages
import app.scanova.core.data.export.PdfRenderSizing
import app.scanova.core.data.export.PixelSize
import app.scanova.core.data.model.PageEdit
import app.scanova.core.data.model.PageFilters
import app.scanova.core.data.pdf.PdfWriter
import app.scanova.core.data.scan.PdfProducer
import app.scanova.feature.tools.importer.ImportImages
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Makes new documents for the tools: stages page images in the encrypted
 * store (from pictures, PDFs or existing documents) and creates documents from
 * them with [DocumentEditor.createDocument]. Nothing is written unencrypted.
 *
 * Every function that stages pages either returns all of them or discards
 * what it staged before failing; [discard] drops pages that end up unused.
 */
internal class DocumentBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val editor: DocumentEditor,
    private val documents: DocumentRepository,
    private val dispatchers: AppDispatchers,
    private val clock: TimeSource,
) {

    /** The name a picked file shows in the system picker, when the provider tells it. */
    suspend fun displayName(uri: Uri): String? = withContext(dispatchers.io) {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (e: RuntimeException) {
            null
        }
    }

    /** The MIME type of a picked file, from its provider (or its name). */
    suspend fun mimeType(uri: Uri, name: String?): String? = withContext(dispatchers.io) {
        val reported = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        reported ?: when {
            name == null -> null
            name.endsWith(".pdf", ignoreCase = true) -> PDF_MIME
            else -> null
        }
    }

    /** A picture as a new page: original kept, current image upright and at most 2400 px, recipe "original". */
    suspend fun stageImage(uri: Uri): PageDraft {
        val name = displayName(uri)
        val bytes = withContext(dispatchers.io) {
            try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            } catch (e: OutOfMemoryError) {
                throw ToolException(ToolError.TooLarge, e)
            }
        } ?: throw ToolException(ToolError.Unreadable(name))
        val image = withContext(dispatchers.default) {
            try {
                ImportImages.prepare(bytes)
            } catch (e: OutOfMemoryError) {
                throw ToolException(ToolError.TooLarge, e)
            } catch (e: IllegalArgumentException) {
                null
            }
        } ?: throw ToolException(ToolError.Unreadable(name))
        return store(
            original = image.original,
            current = image.current,
            size = image.currentSize,
            edit = PageEdit(sourceRotation = image.sourceRotation, crop = null, filter = PageFilters.ORIGINAL),
        )
    }

    /** Every page of a PDF the user picked, drawn at 200 dpi (long edge at most 3000 px). */
    suspend fun stagePdf(uri: Uri, onPage: (done: Int, total: Int) -> Unit): List<PageDraft> {
        val name = displayName(uri)
        val pdf = withContext(dispatchers.io) { openPickedPdf(uri, name) }
        return pdf.use { pages ->
            if (pages.pageCount == 0) throw ToolException(ToolError.Unreadable(name))
            renderPages(pages, listOf((0 until pages.pageCount).toList()), name, onPage).single()
        }
    }

    /**
     * Copies pages of a saved document (current image, original, text and
     * recipe) under new ids, grouped as [parts] (0-based page indexes; null:
     * every page, one group). The document itself is not changed. A document
     * whose pages are not on this phone has its PDF drawn instead (Android 11+).
     */
    suspend fun copyPages(
        documentId: String,
        parts: List<List<Int>>? = null,
        onPage: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<List<PageDraft>> {
        val stored = editor.pages(documentId).orThrow()
        if (stored.isNotEmpty()) {
            val wanted = parts ?: listOf(stored.indices.toList())
            val total = wanted.sumOf { it.size }
            var done = 0
            val staged = mutableListOf<PageDraft>()
            return stagingAll(staged) {
                wanted.map { part ->
                    part.map { index ->
                        currentCoroutineContext().ensureActive()
                        editor.duplicatePage(stored[index].id).orThrow().also {
                            staged += it
                            onPage(++done, total)
                        }
                    }
                }
            }
        }
        val pdf = openStoredPdf(documentId)
        return pdf.use { pages -> renderPages(pages, parts ?: listOf((0 until pages.pageCount).toList()), null, onPage) }
    }

    /** Creates a document from staged [pages]; returns its id. Discards the pages when it fails. */
    suspend fun create(title: String, pages: List<PageDraft>): String {
        val now = clock.now()
        return when (val created = editor.createDocument(title, now, pages, pdfProducer(pages, title, now))) {
            is AppResult.Success -> created.value
            is AppResult.Failure -> {
                withContext(NonCancellable) { editor.discard(pages) }
                throw ToolException(ToolError.Storage(created.error))
            }
        }
    }

    suspend fun discard(pages: Collection<PageDraft>) {
        if (pages.isNotEmpty()) withContext(NonCancellable) { editor.discard(pages) }
    }

    /** Small images of a saved document's pages, for choosing pages (e.g. to split). */
    suspend fun openThumbnails(documentId: String): PageThumbnails {
        val stored = editor.pages(documentId).orThrow()
        if (stored.isNotEmpty()) {
            return object : PageThumbnails() {
                override val count: Int = stored.size

                override suspend fun draw(index: Int): Bitmap? {
                    val jpeg = (editor.loadImage(stored[index].image) as? AppResult.Success)?.value ?: return null
                    return withContext(dispatchers.default) {
                        ExportBitmaps.decodeAtLeast(jpeg, THUMBNAIL_LONG_EDGE)?.let { ExportBitmaps.fitWithin(it, THUMBNAIL_LONG_EDGE) }
                    }
                }

                override fun close() = clear()
            }
        }
        val pdf = openStoredPdf(documentId)
        return object : PageThumbnails() {
            override val count: Int = pdf.pageCount

            override suspend fun draw(index: Int): Bitmap = withContext(dispatchers.default) {
                val points = pdf.pointSize(index)
                pdf.render(index, PdfRenderSizing.pixelSize(points.width, points.height, THUMBNAIL_DPI, THUMBNAIL_LONG_EDGE))
            }

            override fun close() {
                clear()
                pdf.close()
            }
        }
    }

    // ── Internals ───────────────────────────────────────────────────────

    private suspend fun store(original: ByteArray, current: ByteArray, size: PixelSize, edit: PageEdit): PageDraft {
        val id = editor.newPageId()
        editor.storeOriginal(id, original).orThrow()
        val handle = editor.storeRendered(id, current).orThrow()
        return PageDraft(id = id, image = handle, width = size.width, height = size.height, ocrText = "", edit = edit)
    }

    private suspend fun renderPages(
        pdf: PdfPages,
        parts: List<List<Int>>,
        fileName: String?,
        onPage: (Int, Int) -> Unit,
    ): List<List<PageDraft>> {
        val total = parts.sumOf { it.size }
        var done = 0
        val staged = mutableListOf<PageDraft>()
        return stagingAll(staged) {
            parts.map { part ->
                part.map { index ->
                    currentCoroutineContext().ensureActive()
                    val (jpeg, size) = withContext(dispatchers.default) { renderPage(pdf, index, fileName) }
                    // A rendered PDF page is both the original and the current image.
                    store(jpeg, jpeg, size, PageEdit()).also {
                        staged += it
                        onPage(++done, total)
                    }
                }
            }
        }
    }

    private fun renderPage(pdf: PdfPages, index: Int, fileName: String?): Pair<ByteArray, PixelSize> {
        val bitmap = try {
            val points = pdf.pointSize(index)
            pdf.render(
                index,
                PdfRenderSizing.pixelSize(
                    points.width,
                    points.height,
                    PdfRenderSizing.IMPORT_DPI,
                    PdfRenderSizing.IMPORT_MAX_LONG_EDGE,
                ),
            )
        } catch (e: OutOfMemoryError) {
            throw ToolException(ToolError.TooLarge, e)
        } catch (e: IllegalStateException) {
            throw ToolException(ToolError.Unreadable(fileName), e)
        } catch (e: IllegalArgumentException) {
            throw ToolException(ToolError.Unreadable(fileName), e)
        }
        return try {
            ExportBitmaps.toJpeg(bitmap, PAGE_QUALITY) to PixelSize(bitmap.width, bitmap.height)
        } catch (e: IOException) {
            throw ToolException(ToolError.Unreadable(fileName), e)
        } finally {
            bitmap.recycle()
        }
    }

    /** Runs [block]; if it fails or is cancelled, the pages staged so far are discarded. */
    private suspend fun <T> stagingAll(staged: List<PageDraft>, block: suspend () -> T): T = try {
        block()
    } catch (e: Throwable) {
        withContext(NonCancellable) { editor.discard(staged.toList()) }
        throw e
    }

    /** Opens a PDF the user picked. Throws [ToolException] when it cannot be read. */
    private fun openPickedPdf(uri: Uri, name: String?): PdfPages {
        val descriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: FileNotFoundException) {
            null
        } catch (e: SecurityException) {
            null
        } ?: throw ToolException(ToolError.Unreadable(name))
        return try {
            PdfPages.open(descriptor)
        } catch (e: SecurityException) {
            throw ToolException(ToolError.PasswordProtected(name), e)
        } catch (e: IOException) {
            throw ToolException(ToolError.Unreadable(name), e)
        } catch (e: IllegalArgumentException) {
            // Some providers hand out a pipe, which PdfRenderer cannot seek in: read the file into memory instead.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw ToolException(ToolError.Unreadable(name), e)
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (io: IOException) {
                null
            } catch (oom: OutOfMemoryError) {
                throw ToolException(ToolError.TooLarge, oom)
            } ?: throw ToolException(ToolError.Unreadable(name), e)
            openInMemory(bytes, name)
        }
    }

    /** Opens the stored PDF of a document, in memory only. */
    private suspend fun openStoredPdf(documentId: String): PdfPages {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw ToolException(ToolError.NeedsNewerAndroid)
        val bytes = documents.readPdf(documentId) { it.readBytes() }.orThrow()
        return withContext(dispatchers.default) { openInMemory(bytes, null) }
    }

    private fun openInMemory(bytes: ByteArray, name: String?): PdfPages {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw ToolException(ToolError.NeedsNewerAndroid)
        return try {
            PdfPages.fromMemory(bytes)
        } catch (e: SecurityException) {
            throw ToolException(ToolError.PasswordProtected(name), e)
        } catch (e: IOException) {
            throw ToolException(ToolError.Unreadable(name), e)
        } catch (e: IllegalArgumentException) {
            throw ToolException(ToolError.Unreadable(name), e)
        } catch (e: android.system.ErrnoException) {
            throw ToolException(ToolError.Unreadable(name), e)
        }
    }

    private fun <T> AppResult<T>.orThrow(): T = when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> throw ToolException(ToolError.Storage(error))
    }

    companion object {
        const val PDF_MIME = "application/pdf"

        /** JPEG quality of pages drawn from a PDF (same as scanned pages). */
        private const val PAGE_QUALITY = 85
        private const val THUMBNAIL_LONG_EDGE = 360
        private const val THUMBNAIL_DPI = 60
    }
}

/** Builds a document's PDF from staged pages, in order, each page one JPEG. */
internal fun pdfProducer(pages: List<PageDraft>, title: String, createdAt: Instant): PdfProducer =
    PdfProducer { output, readPage ->
        val writer = PdfWriter(output, createdAt, title)
        pages.forEach { writer.addJpegPage(readPage(it.id)) }
        writer.finish()
    }

/** Small page images, decoded on demand and cached (a few megabytes). Close when done. */
internal abstract class PageThumbnails : Closeable {
    abstract val count: Int

    private val cache = object : LruCache<Int, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    protected abstract suspend fun draw(index: Int): Bitmap?

    /** Page [index] (0-based), or null when it cannot be shown. */
    suspend fun load(index: Int): Bitmap? {
        cache.get(index)?.let { return it }
        val bitmap = try {
            draw(index)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null
        cache.put(index, bitmap)
        return bitmap
    }

    protected fun clear() = cache.evictAll()

    private companion object {
        const val CACHE_BYTES = 16 * 1024 * 1024
    }
}
