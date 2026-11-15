package app.scanova.feature.scan.session

import android.graphics.Bitmap
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.scan.ScanRepository
import app.scanova.feature.scan.geometry.Quad
import app.scanova.feature.scan.imaging.Bitmaps
import app.scanova.feature.scan.imaging.PageRenderer
import app.scanova.feature.scan.ocr.PageTextRecognizer
import javax.inject.Inject

/**
 * Turns a stored capture into a finished page: flatten the outlined area,
 * apply the filter, store the result encrypted, and read its text.
 *
 * Shared by the crop screen (new outline) and the review screen (new look for
 * a page that is already finished). Heavy work: call it on a background dispatcher.
 */
internal class PageFinisher @Inject constructor(
    private val scanRepository: ScanRepository,
    private val textRecognizer: PageTextRecognizer,
) {
    private val renderer = PageRenderer()

    class Output(
        val page: FinishedPage,
        /** The finished page at preview size, for the review screen's large view. */
        val preview: Bitmap,
    )

    /**
     * Renders and stores page [pageId] from [captureJpeg]. When [knownText] is
     * given the text is not read again: only the look changed, and recognition
     * runs on the unfiltered page anyway. Returns null when the page could not
     * be made or stored.
     */
    suspend fun finish(
        pageId: String,
        captureJpeg: ByteArray,
        outline: Quad,
        filter: ScanFilter,
        knownText: String? = null,
    ): Output? {
        val photo = Bitmaps.decodeSampled(captureJpeg, CAPTURE_LONG_EDGE) ?: return null
        var flat: Bitmap? = null
        var page: Bitmap? = null
        try {
            flat = renderer.flatten(photo, outline, PAGE_LONG_EDGE)
            page = renderer.applyFilter(flat, filter)
            val stored = scanRepository.storePage(pageId, Bitmaps.toJpeg(page, PAGE_JPEG_QUALITY))
            if (stored is AppResult.Failure) return null
            // Recognition reads the unfiltered page: thresholding can erase thin strokes.
            val text = knownText ?: textRecognizer.recognize(flat)
            return Output(
                page = FinishedPage(
                    width = page.width,
                    height = page.height,
                    ocrText = text,
                    thumbnail = Bitmaps.thumbnail(page, THUMBNAIL_LONG_EDGE),
                ),
                preview = Bitmaps.thumbnail(page, PREVIEW_LONG_EDGE),
            )
        } catch (e: OutOfMemoryError) {
            return null
        } finally {
            photo.recycle()
            if (page !== flat) page?.recycle()
            flat?.recycle()
        }
    }

    companion object {
        /** Longest side of the large page view on the review screen. */
        const val PREVIEW_LONG_EDGE = 1400

        private const val CAPTURE_LONG_EDGE = 2560
        private const val PAGE_LONG_EDGE = 2400
        private const val THUMBNAIL_LONG_EDGE = 360
        private const val PAGE_JPEG_QUALITY = 85
    }
}
