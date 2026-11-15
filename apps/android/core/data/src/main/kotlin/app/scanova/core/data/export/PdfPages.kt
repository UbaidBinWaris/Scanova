package app.scanova.core.data.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import java.io.Closeable

/**
 * Draws the pages of a PDF into bitmaps with Android's `PdfRenderer`.
 *
 * A decrypted document never touches storage: [fromMemory] puts the bytes in
 * an anonymous in-memory file (`memfd`, public API from Android 11) instead of
 * a file on disk. A PDF the user picked from their own files is opened from
 * its descriptor with [open].
 *
 * `PdfRenderer` can only have one page open at a time, hence the lock. Not
 * thread-safe beyond that; close it when done.
 */
class PdfPages private constructor(private val renderer: PdfRenderer) : Closeable {

    val pageCount: Int get() = renderer.pageCount

    /** Size of page [index] in PDF points (1/72 inch). */
    fun pointSize(index: Int): PixelSize = synchronized(renderer) {
        renderer.openPage(index).use { page -> PixelSize(page.width, page.height) }
    }

    /** Draws page [index] at exactly [size] pixels, on white (PDF pages have no background of their own). */
    fun render(index: Int, size: PixelSize): Bitmap = synchronized(renderer) {
        renderer.openPage(index).use { page ->
            val bitmap = createBitmap(size.width, size.height)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    override fun close() = synchronized(renderer) { renderer.close() }

    companion object {
        /** Whether a PDF held only in memory can be drawn on this phone (Android 11 and later). */
        @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
        val canOpenFromMemory: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        /**
         * Opens a PDF from a seekable [descriptor], which the renderer then owns
         * (it is closed with this object, or right away if opening fails).
         *
         * @throws SecurityException the PDF is password protected.
         * @throws java.io.IOException the file is not a PDF or is damaged.
         * @throws IllegalArgumentException the descriptor is not seekable.
         */
        fun open(descriptor: ParcelFileDescriptor): PdfPages {
            val renderer = try {
                PdfRenderer(descriptor)
            } catch (e: Exception) {
                runCatching { descriptor.close() }
                throw e
            }
            return PdfPages(renderer)
        }

        /** Opens PDF bytes without writing them anywhere. Same exceptions as [open]. */
        @RequiresApi(Build.VERSION_CODES.R)
        fun fromMemory(pdf: ByteArray): PdfPages {
            val memory = Os.memfd_create("scanova-pdf", 0)
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
            return open(descriptor)
        }
    }
}
