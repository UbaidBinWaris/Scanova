package app.scanova.feature.documents.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import java.io.Closeable
import kotlin.math.roundToInt

/**
 * Renders a decrypted PDF without it ever touching storage.
 *
 * Android's `PdfRenderer` needs a seekable file descriptor. Instead of a file
 * on disk, the bytes are put into an anonymous in-memory file (`memfd`), which
 * exists only in RAM and disappears when it is closed. `memfd_create` is public
 * API from Android 11, which is why previewing documents from other devices
 * needs that version.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal class InMemoryPdf(pdf: ByteArray) : Closeable {
    private val renderer: PdfRenderer

    init {
        val memory = Os.memfd_create("scanova-document", 0)
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
        renderer = try {
            PdfRenderer(descriptor)
        } catch (e: Exception) {
            descriptor.close()
            throw e
        }
    }

    val pageCount: Int get() = renderer.pageCount

    /** Width divided by height of each page. */
    fun aspectRatios(): List<Float> = synchronized(renderer) {
        (0 until renderer.pageCount).map { index ->
            renderer.openPage(index).use { page -> if (page.height > 0) page.width.toFloat() / page.height else 1f }
        }
    }

    /** Draws one page [width] pixels wide. Only one page can be open at a time, hence the lock. */
    fun render(index: Int, width: Int): Bitmap = synchronized(renderer) {
        renderer.openPage(index).use { page ->
            val height = (width.toFloat() * page.height / page.width).roundToInt().coerceAtLeast(1)
            val bitmap = createBitmap(width, height)
            // PDF pages have no background of their own.
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    override fun close() = synchronized(renderer) { renderer.close() }
}
