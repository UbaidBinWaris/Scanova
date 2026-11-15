package app.scanova.feature.scan.session

import app.scanova.core.common.result.AppResult
import app.scanova.core.data.model.PageEdit
import app.scanova.core.data.scan.ScanRepository
import app.scanova.feature.scan.imaging.Bitmaps
import app.scanova.feature.scan.ocr.PageTextRecognizer
import app.scanova.feature.scan.render.PageRecipeRenderer
import javax.inject.Inject

/**
 * Turns a stored capture into a finished page: render its recipe through the
 * shared [PageRecipeRenderer], store the result encrypted, and read its text.
 *
 * The scan renders from the capture's working copy, which is already upright,
 * so the recipe's `sourceRotation` is ignored here (it is kept in the saved
 * recipe for the original). Heavy work: call it on a background dispatcher.
 */
internal class PageFinisher @Inject constructor(
    private val scanRepository: ScanRepository,
    private val textRecognizer: PageTextRecognizer,
    private val renderer: PageRecipeRenderer,
) {
    /**
     * Renders and stores page [pageId] from [captureJpeg]. When [knownText] is
     * given the text is not read again (only the look changed; recognition
     * runs on the unfiltered page anyway). Returns null when the page could
     * not be made or stored.
     */
    suspend fun finish(pageId: String, captureJpeg: ByteArray, edit: PageEdit, knownText: String? = null): FinishedPage? {
        val working = edit.copy(sourceRotation = 0, markup = emptyList())
        val photo = Bitmaps.decodeWithin(captureJpeg, CAPTURE_LONG_EDGE, MIN_CAPTURE_LONG_EDGE) ?: return null
        var rendered: PageRecipeRenderer.Rendered? = null
        try {
            rendered = renderer.render(photo, working, PAGE_LONG_EDGE, keepFlat = knownText == null)
            photo.recycle()
            val page = rendered.page
            val stored = scanRepository.storePage(pageId, Bitmaps.toJpeg(page, PAGE_JPEG_QUALITY))
            if (stored is AppResult.Failure) return null
            // Recognition reads the unfiltered page: thresholding can erase thin strokes.
            val text = knownText ?: rendered.flat?.let { textRecognizer.recognize(it) }.orEmpty()
            return FinishedPage(
                width = page.width,
                height = page.height,
                ocrText = text,
                thumbnail = Bitmaps.thumbnail(page, THUMBNAIL_LONG_EDGE),
                recipe = working,
            )
        } catch (e: OutOfMemoryError) {
            return null
        } finally {
            if (!photo.isRecycled) photo.recycle()
            rendered?.recycle()
        }
    }

    companion object {
        /** Longest side of a finished page: about 290 dpi on A4. Shared with the editor. */
        const val PAGE_LONG_EDGE = 2400
        const val PAGE_JPEG_QUALITY = 85
        const val THUMBNAIL_LONG_EDGE = 360

        /** Longest side kept from a photo for the working copy: enough for a sharp page. */
        const val CAPTURE_LONG_EDGE = 2560
        private const val MIN_CAPTURE_LONG_EDGE = 1200
    }
}
