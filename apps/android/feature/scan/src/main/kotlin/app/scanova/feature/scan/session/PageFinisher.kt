package app.scanova.feature.scan.session

import android.graphics.Bitmap
import android.graphics.Matrix
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.model.PageEdit
import app.scanova.core.data.scan.ScanRepository
import app.scanova.feature.scan.imaging.Bitmaps
import app.scanova.feature.scan.ocr.PageTextRecognizer
import app.scanova.feature.scan.render.PageRecipeRenderer
import app.scanova.feature.scan.render.RecipeGeometry
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
     *
     * With [detectTurn], text recognition also says which way up the page is
     * (sideways or upside down, e.g. a landscape certificate shot in
     * portrait). When it is not upright, the page is stored turned the right
     * way and the returned [FinishedPage.recipe] carries the turn in
     * [PageEdit.rotation] — a recipe change like any other, so it stays
     * non-destructive and the user can turn the page back.
     *
     * [cleaned] is the page's cleaned layer when its look is Server clean-up
     * (made beforehand from [flat]); without it such a page gets the stand-in
     * look. It is left alone.
     */
    suspend fun finish(
        pageId: String,
        captureJpeg: ByteArray,
        edit: PageEdit,
        knownText: String? = null,
        detectTurn: Boolean = false,
        cleaned: Bitmap? = null,
    ): FinishedPage? {
        val working = edit.copy(sourceRotation = 0, markup = emptyList())
        val readText = knownText == null || detectTurn
        val photo = Bitmaps.decodeWithin(captureJpeg, CAPTURE_LONG_EDGE, MIN_CAPTURE_LONG_EDGE) ?: return null
        var rendered: PageRecipeRenderer.Rendered? = null
        var turned: Bitmap? = null
        try {
            rendered = renderer.render(photo, working, PAGE_LONG_EDGE, keepFlat = readText, cleaned = cleaned)
            photo.recycle()
            // Recognition reads the unfiltered page: thresholding can erase thin strokes.
            val reading = if (readText) rendered.flat?.let { textRecognizer.read(it, detectTurn) } else null
            val turn = reading?.turn ?: 0
            val recipe = if (turn == 0) working else working.copy(rotation = RecipeGeometry.normalizeDegrees(working.rotation + turn))
            // Turning the rendered page gives the page the recipe with the turn renders (the turn is
            // the last step of the geometry and the look does not depend on which way is up), at a
            // fraction of the cost of rendering it again.
            val page = if (turn == 0) rendered.page else quarterTurned(rendered.page, turn).also { turned = it }
            val stored = scanRepository.storePage(pageId, Bitmaps.toJpeg(page, PAGE_JPEG_QUALITY))
            if (stored is AppResult.Failure) return null
            return FinishedPage(
                width = page.width,
                height = page.height,
                ocrText = knownText?.takeIf { reading == null } ?: reading?.text.orEmpty(),
                thumbnail = Bitmaps.thumbnail(page, THUMBNAIL_LONG_EDGE),
                recipe = recipe,
            )
        } catch (e: OutOfMemoryError) {
            return null
        } finally {
            if (!photo.isRecycled) photo.recycle()
            rendered?.recycle()
            turned?.recycle()
        }
    }

    /**
     * The page's geometry alone (no look) at [PAGE_LONG_EDGE], made the way
     * [finish] makes it: what Server clean-up sends to the server. A new
     * bitmap; null when the capture cannot be decoded or memory is short.
     */
    fun flat(captureJpeg: ByteArray, edit: PageEdit): Bitmap? {
        val photo = Bitmaps.decodeWithin(captureJpeg, CAPTURE_LONG_EDGE, MIN_CAPTURE_LONG_EDGE) ?: return null
        return try {
            renderer.geometry(photo, edit.copy(sourceRotation = 0), PAGE_LONG_EDGE)
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            photo.recycle()
        }
    }

    /** A new bitmap: [page] turned [degrees] clockwise (a multiple of 90). [page] is left alone. */
    private fun quarterTurned(page: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(page, 0, 0, page.width, page.height, matrix, true)
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
