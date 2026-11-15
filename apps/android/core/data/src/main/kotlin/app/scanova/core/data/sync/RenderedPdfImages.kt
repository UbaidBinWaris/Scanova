package app.scanova.core.data.sync

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import app.scanova.core.common.log.ScanovaLog
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Fallback for PDFs that [WrittenPdfImages] cannot read: draws every page with
 * Android's [PdfRenderer] and stores it as a JPEG. Slower and not lossless, so
 * it is only used when the page images cannot be taken out of the file as they are.
 *
 * The PDF is decrypted into an anonymous in-memory file (`memfd`), never onto
 * storage. `memfd_create` is public API from Android 11, so older versions
 * return null (the document stays readable as a PDF; it just has no page images).
 */
internal class RenderedPdfImages(
    private val maxPdfBytes: Long = DEFAULT_MAX_PDF_BYTES,
) : PdfPageImages {

    override fun pages(open: () -> InputStream, onPage: (index: Int, jpeg: ByteArray) -> Unit): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val pdf = open().use { readBounded(it) } ?: return null
            render(pdf, onPage)
        } catch (e: IOException) {
            ScanovaLog.warn(TAG, "pdf_render_failed", e)
            null
        } catch (e: ErrnoException) {
            ScanovaLog.warn(TAG, "pdf_render_failed", e)
            null
        } catch (e: SecurityException) {
            // Password-protected PDF.
            ScanovaLog.warn(TAG, "pdf_render_refused", e)
            null
        } catch (e: IllegalStateException) {
            ScanovaLog.warn(TAG, "pdf_render_failed", e)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun render(pdf: ByteArray, onPage: (Int, ByteArray) -> Unit): Int {
        val memory = Os.memfd_create("scanova-restore", 0)
        val descriptor = try {
            var written = 0
            while (written < pdf.size) {
                written += Os.write(memory, pdf, written, pdf.size - written)
            }
            Os.lseek(memory, 0, OsConstants.SEEK_SET)
            ParcelFileDescriptor.dup(memory)
        } finally {
            Os.close(memory)
        }
        return PdfRenderer(descriptor).use { renderer ->
            for (index in 0 until renderer.pageCount) {
                val jpeg = renderer.openPage(index).use { page ->
                    // Points are 1/72 inch; 200 dpi keeps text sharp, bounded so one page stays small in memory.
                    val scale = minOf(DPI / POINTS_PER_INCH, MAX_SIDE_PIXELS.toFloat() / max(page.width, page.height))
                    val bitmap = createBitmap(
                        (page.width * scale).roundToInt().coerceAtLeast(1),
                        (page.height * scale).roundToInt().coerceAtLeast(1),
                    )
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                            .toByteArray()
                    } finally {
                        bitmap.recycle()
                    }
                }
                onPage(index, jpeg)
            }
            renderer.pageCount
        }
    }

    /** The whole PDF, or null when it is larger than this phone should hold in memory. */
    private fun readBounded(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n == -1) return out.toByteArray()
            total += n
            if (total > maxPdfBytes) return null
            out.write(buffer, 0, n)
        }
    }

    private companion object {
        const val TAG = "PageRestore"
        const val DEFAULT_MAX_PDF_BYTES = 64L * 1024 * 1024
        const val DPI = 200f
        const val POINTS_PER_INCH = 72f
        const val MAX_SIDE_PIXELS = 2400
        const val JPEG_QUALITY = 88
        const val BUFFER_BYTES = 64 * 1024
    }
}
