package app.scanova.feature.scan.filter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import app.scanova.core.common.log.ScanovaLog
import app.scanova.feature.scan.imaging.OpenCvSupport
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Applies a catalogue filter ([FilterCatalogue]) to a flattened page.
 *
 * The document filters do pixel work with OpenCV; when the native library is
 * missing (or fails) each falls back to a colour matrix in the same spirit, so
 * a filter always does something sensible. Colour filters are colour matrices
 * on every phone.
 *
 * Works at any size: kernel sizes follow the image, so a 200-pixel preview
 * thumbnail looks like a small copy of the full page.
 */
internal class FilterEngine @Inject constructor() {

    fun apply(page: Bitmap, filterId: String): Bitmap = apply(page, FilterCatalogue.byId(filterId))

    /** A new bitmap with [filter] applied, or [page] itself when the filter leaves it unchanged. */
    fun apply(page: Bitmap, filter: PageFilter): Bitmap {
        val algorithm = filter.algorithm
        if (algorithm == FilterAlgorithm.NONE) return page
        ColorMatrices.forAlgorithm(algorithm)?.let { return withMatrix(page, it) }
        if (OpenCvSupport.isAvailable && page.width >= MIN_SIDE && page.height >= MIN_SIDE) {
            try {
                return withOpenCv(page, algorithm)
            } catch (e: Exception) {
                // CvException is a RuntimeException; OutOfMemoryError is left to the caller.
                ScanovaLog.warn(TAG, "opencv_filter_failed_using_matrix", e)
            }
        }
        val fallback = ColorMatrices.fallback(algorithm) ?: return page
        return withMatrix(page, fallback)
    }

    private fun withMatrix(page: Bitmap, matrix: FloatArray): Bitmap {
        val result = createBitmap(page.width, page.height)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(matrix)) }
        Canvas(result).drawBitmap(page, 0f, 0f, paint)
        return result
    }

    private fun withOpenCv(page: Bitmap, algorithm: FilterAlgorithm): Bitmap = Mats().use { mats ->
        val rgba = mats.new()
        Utils.bitmapToMat(page, rgba)
        val out = when (algorithm) {
            FilterAlgorithm.AUTO -> colourPage(rgba, EnhanceStyle.NATURAL, AUTO_SATURATION, AUTO_SHARPEN, mats)
            FilterAlgorithm.MAGIC_COLOR -> colourPage(rgba, EnhanceStyle.VIVID, MAGIC_SATURATION, AUTO_SHARPEN, mats)
            FilterAlgorithm.NO_SHADOW -> colourPage(rgba, EnhanceStyle.EVEN, 1f, 0.0, mats)
            FilterAlgorithm.SHARPEN -> colourPage(rgba, EnhanceStyle.NATURAL, AUTO_SATURATION, CLEAR_SHARPEN, mats)
            FilterAlgorithm.LIGHTEN -> colourPage(rgba, EnhanceStyle.EVEN, 1f, 0.0, mats).also { Core.LUT(it, lut(LIGHTEN_CURVE, mats), it) }
            FilterAlgorithm.GREYSCALE -> greyPage(rgba, EnhanceStyle.NATURAL, mats)
            FilterAlgorithm.INK_SAVER -> greyPage(rgba, EnhanceStyle.NATURAL, mats).also { Core.LUT(it, lut(INK_SAVER_CURVE, mats), it) }
            FilterAlgorithm.BLACK_WHITE -> blackWhite(rgba, sharp = false, mats)
            FilterAlgorithm.BW_SHARP -> blackWhite(rgba, sharp = true, mats)
            else -> rgba
        }
        val rgbaOut = mats.new()
        when (out.channels()) {
            1 -> Imgproc.cvtColor(out, rgbaOut, Imgproc.COLOR_GRAY2RGBA)
            3 -> Imgproc.cvtColor(out, rgbaOut, Imgproc.COLOR_RGB2RGBA)
            else -> out.copyTo(rgbaOut)
        }
        val result = createBitmap(page.width, page.height)
        Utils.matToBitmap(rgbaOut, result)
        result
    }

    // ── Filters (all built on DocumentEnhancer) ─────────────────────────

    /**
     * An enhanced colour page (8-bit RGB): [saturation] boosts colourful pixels
     * only (paper and black ink stay neutral), any tint a reflection left is
     * removed, and [sharpen] adds a small unsharp mask.
     */
    private fun colourPage(rgba: Mat, style: EnhanceStyle, saturation: Float, sharpen: Double, mats: Mats): Mat {
        val enhanced = DocumentEnhancer.enhance(rgba, style, mats)
        val rgb8 = mats.new()
        enhanced.rgb.convertTo(rgb8, CvType.CV_8UC3, 255.0)
        val hsv = mats.new()
        Imgproc.cvtColor(rgb8, hsv, Imgproc.COLOR_RGB2HSV)
        val planes = mats.split(hsv)
        if (saturation != 1f) Core.LUT(planes[1], lut(saturationCurve(saturation), mats), planes[1])
        // A reflection carries no colour of its own: the tint it left on paper is noise.
        // Only pale, paper-like pixels lose it; ink inside the reflection keeps its colour.
        val paperLike = mats.new()
        Core.LUT(planes[2], lut(PAPER_LIKE_CURVE, mats), paperLike)
        val glare8 = mats.new()
        enhanced.glare.convertTo(glare8, CvType.CV_8U, 255.0)
        val tintDrop = mats.new()
        Core.multiply(glare8, paperLike, tintDrop, 1.0 / 255)
        val keep = mats.new()
        Core.bitwise_not(tintDrop, keep)
        Core.multiply(planes[1], keep, planes[1], 1.0 / 255)
        Core.merge(planes, hsv)
        Imgproc.cvtColor(hsv, rgb8, Imgproc.COLOR_HSV2RGB)
        if (sharpen > 0.0) unsharp(rgb8, sharpenSigma(rgb8), sharpen, mats)
        return rgb8
    }

    /** An enhanced page in greys (8-bit, one channel). */
    private fun greyPage(rgba: Mat, style: EnhanceStyle, mats: Mats): Mat {
        val enhanced = DocumentEnhancer.enhance(rgba, style, mats)
        val rgb8 = mats.new()
        enhanced.rgb.convertTo(rgb8, CvType.CV_8UC3, 255.0)
        val gray = mats.new()
        Imgproc.cvtColor(rgb8, gray, Imgproc.COLOR_RGB2GRAY)
        return gray
    }

    /**
     * Black ink on white paper. The page is enhanced first (light evened out,
     * noise and glare cleaned up), so one threshold (Otsu's, kept within sane
     * bounds) separates ink from paper everywhere; dots left by the threshold
     * are removed. [sharp] deepens and sharpens the ink first, for small print.
     */
    private fun blackWhite(rgba: Mat, sharp: Boolean, mats: Mats): Mat {
        val gray = greyPage(rgba, if (sharp) EnhanceStyle.VIVID else EnhanceStyle.NATURAL, mats)
        if (sharp) unsharp(gray, sigma = 1.0, amount = 0.5, mats)
        val scratch = mats.new()
        val otsu = Imgproc.threshold(gray, scratch, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        val threshold = otsu.coerceIn(BW_MIN_THRESHOLD, if (sharp) BW_SHARP_MAX_THRESHOLD else BW_MAX_THRESHOLD)
        Imgproc.threshold(gray, gray, threshold, 255.0, Imgproc.THRESH_BINARY)
        val longEdge = max(gray.cols(), gray.rows())
        DocumentEnhancer.removeDots(gray, (BW_DOT_AREA * (longEdge / 1000.0) * (longEdge / 1000.0)).toInt(), mats)
        return gray
    }

    // ── Building blocks ─────────────────────────────────────────────────

    private fun sharpenSigma(image: Mat): Double = max(0.8, max(image.cols(), image.rows()) / 2400.0)

    private fun unsharp(image: Mat, sigma: Double, amount: Double, mats: Mats) {
        val blurred = mats.new()
        Imgproc.GaussianBlur(image, blurred, Size(0.0, 0.0), sigma)
        Core.addWeighted(image, 1.0 + amount, blurred, -amount, 0.0, image)
    }

    private fun saturationCurve(boost: Float): ByteArray = ByteArray(LEVELS) { level ->
        (EnhanceMath.saturation(level / 255f, boost) * 255f).roundToInt().coerceIn(0, 255).toByte()
    }

    private fun lut(curve: ByteArray, mats: Mats): Mat {
        val table = mats.track(Mat(1, curve.size, CvType.CV_8UC1))
        table.put(0, 0, curve)
        return table
    }

    private companion object {
        const val TAG = "FilterEngine"
        const val MIN_SIDE = 8
        const val LEVELS = 256
        const val AUTO_SATURATION = 1.06f
        const val MAGIC_SATURATION = 1.22f
        const val AUTO_SHARPEN = 0.35
        const val CLEAR_SHARPEN = 0.9
        const val BW_MIN_THRESHOLD = 120.0
        const val BW_MAX_THRESHOLD = 205.0
        const val BW_SHARP_MAX_THRESHOLD = 215.0

        /** Black-and-white dots smaller than this (in pixels of a 1000-pixel page) are dirt. */
        const val BW_DOT_AREA = 6.0

        val LIGHTEN_CURVE: ByteArray by lazy { ToneCurves.toBytes(ToneCurves.gamma(0.72)) }

        /** 0 for ink, 255 for paper-white (HSV value): where a reflection's tint may be dropped. */
        val PAPER_LIKE_CURVE: ByteArray by lazy {
            ByteArray(LEVELS) { level ->
                (EnhanceMath.smoothstep(0.80f, 0.97f, level / 255f) * 255f).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        val INK_SAVER_CURVE: ByteArray by lazy { ToneCurves.toBytes(ToneCurves.inkSaver()) }
    }
}
