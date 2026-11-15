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
import kotlin.math.min
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
        val out = mats.new()
        Utils.bitmapToMat(page, rgba)
        when (algorithm) {
            FilterAlgorithm.AUTO -> auto(rgba, out, mats)
            FilterAlgorithm.MAGIC_COLOR -> magicColor(rgba, out, mats)
            FilterAlgorithm.NO_SHADOW -> noShadow(rgba, out, mats)
            FilterAlgorithm.LIGHTEN -> lighten(rgba, out, mats)
            FilterAlgorithm.SHARPEN -> sharpen(rgba, out, mats)
            FilterAlgorithm.GREYSCALE -> greyscale(rgba, out, mats)
            FilterAlgorithm.BLACK_WHITE -> blackWhite(rgba, out, mats)
            FilterAlgorithm.BW_SHARP -> blackWhiteSharp(rgba, out, mats)
            FilterAlgorithm.INK_SAVER -> inkSaver(rgba, out, mats)
            else -> rgba.copyTo(out)
        }
        val result = createBitmap(page.width, page.height)
        Utils.matToBitmap(out, result)
        result
    }

    // ── Filters ─────────────────────────────────────────────────────────

    /** Local contrast on lightness only, so colours stay true. Unchanged from the first scanner ("enhanced"). */
    private fun auto(rgba: Mat, out: Mat, mats: Mats) = onLightness(rgba, out, mats) { lightness ->
        equalizeLocally(lightness, clipLimit = 2.0)
    }

    /** Evens out the lighting of each colour channel: paper becomes uniformly light, ink and colours stay. */
    private fun noShadow(rgba: Mat, out: Mat, mats: Mats) {
        val rgb = evenedRgb(rgba, mats)
        Imgproc.cvtColor(rgb, out, Imgproc.COLOR_RGB2RGBA)
    }

    /** No shadow, then white paper, deeper ink and livelier colours. */
    private fun magicColor(rgba: Mat, out: Mat, mats: Mats) {
        val rgb = evenedRgb(rgba, mats)
        Core.LUT(rgb, lut(MAGIC_LEVELS, mats), rgb)
        val hsv = mats.new()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val channels = mats.split(hsv)
        channels[1].convertTo(channels[1], -1, MAGIC_SATURATION, 0.0)
        Core.merge(channels, hsv)
        Imgproc.cvtColor(hsv, rgb, Imgproc.COLOR_HSV2RGB)
        Imgproc.cvtColor(rgb, out, Imgproc.COLOR_RGB2RGBA)
    }

    /** Lifts the mid-tones of the lightness, keeping black black and white white. */
    private fun lighten(rgba: Mat, out: Mat, mats: Mats) = onLightness(rgba, out, mats) { lightness ->
        Core.LUT(lightness, lut(LIGHTEN_CURVE, mats), lightness)
    }

    /** A little local contrast and an unsharp mask on the lightness: crisper strokes, no colour fringes. */
    private fun sharpen(rgba: Mat, out: Mat, mats: Mats) = onLightness(rgba, out, mats) { lightness ->
        equalizeLocally(lightness, clipLimit = 1.6)
        unsharp(lightness, sigma = max(1.0, min(lightness.cols(), lightness.rows()) / SHARPEN_SIGMA_DIVISOR), amount = 0.8, mats)
    }

    /** Unchanged from the first scanner. */
    private fun greyscale(rgba: Mat, out: Mat, mats: Mats) {
        val gray = mats.new()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        equalizeLocally(gray, clipLimit = 1.5)
        Imgproc.cvtColor(gray, out, Imgproc.COLOR_GRAY2RGBA)
    }

    /** Unchanged from the first scanner: a threshold decided per neighbourhood, so shadows do not turn black. */
    private fun blackWhite(rgba: Mat, out: Mat, mats: Mats) {
        val gray = mats.new()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val block = oddAtLeast((min(gray.cols(), gray.rows()) / 16).coerceIn(15, 151), 3)
        Imgproc.adaptiveThreshold(
            gray,
            gray,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            block,
            BLACK_WHITE_OFFSET,
        )
        Imgproc.medianBlur(gray, gray, 3)
        Imgproc.cvtColor(gray, out, Imgproc.COLOR_GRAY2RGBA)
    }

    /**
     * Lighting evened out first, then sharpened and split into ink and paper at
     * one threshold (Otsu's, kept within sane bounds): clean white paper,
     * solid black text, no speckles in shadows.
     */
    private fun blackWhiteSharp(rgba: Mat, out: Mat, mats: Mats) {
        val gray = mats.new()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        evenOut(gray, mats)
        unsharp(gray, sigma = 1.0, amount = 0.5, mats)
        val scratch = mats.new()
        val otsu = Imgproc.threshold(gray, scratch, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        Imgproc.threshold(gray, gray, otsu.coerceIn(BW_SHARP_MIN_THRESHOLD, BW_SHARP_MAX_THRESHOLD), 255.0, Imgproc.THRESH_BINARY)
        Imgproc.cvtColor(gray, out, Imgproc.COLOR_GRAY2RGBA)
    }

    /** Lighting evened out, paper made white and ink printed lighter. */
    private fun inkSaver(rgba: Mat, out: Mat, mats: Mats) {
        val gray = mats.new()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        evenOut(gray, mats)
        Core.LUT(gray, lut(INK_SAVER_CURVE, mats), gray)
        Imgproc.cvtColor(gray, out, Imgproc.COLOR_GRAY2RGBA)
    }

    // ── Building blocks ─────────────────────────────────────────────────

    /** Runs [change] on the lightness channel (Lab), leaving hue and saturation as they were. */
    private inline fun onLightness(rgba: Mat, out: Mat, mats: Mats, change: (Mat) -> Unit) {
        val lab = mats.new()
        Imgproc.cvtColor(rgba, lab, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(lab, lab, Imgproc.COLOR_RGB2Lab)
        val channels = mats.split(lab)
        change(channels[0])
        Core.merge(channels, lab)
        Imgproc.cvtColor(lab, lab, Imgproc.COLOR_Lab2RGB)
        Imgproc.cvtColor(lab, out, Imgproc.COLOR_RGB2RGBA)
    }

    /** The page as RGB with every channel's lighting evened out. */
    private fun evenedRgb(rgba: Mat, mats: Mats): Mat {
        val rgb = mats.new()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val channels = mats.split(rgb)
        channels.forEach { evenOut(it, mats) }
        Core.merge(channels, rgb)
        return rgb
    }

    /**
     * Divides a channel by an estimate of its paper brightness, so paper in
     * shadow and paper in light both end up near white while ink stays dark.
     * The paper is estimated on a small copy: ink is removed by a dilation
     * (light spreads over thin dark strokes), then a wide median smooths it.
     */
    private fun evenOut(channel: Mat, mats: Mats) {
        val cols = channel.cols()
        val rows = channel.rows()
        val longEdge = max(cols, rows)
        val scale = min(1.0, BACKGROUND_LONG_EDGE / longEdge)
        val small = mats.new()
        if (scale < 1.0) {
            val size = Size(max(1.0, (cols * scale).roundToInt().toDouble()), max(1.0, (rows * scale).roundToInt().toDouble()))
            Imgproc.resize(channel, small, size, 0.0, 0.0, Imgproc.INTER_AREA)
        } else {
            channel.copyTo(small)
        }
        val smallLong = max(small.cols(), small.rows())
        val dilation = oddAtLeast(smallLong / DILATION_DIVISOR, 3)
        val kernel = mats.track(Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(dilation.toDouble(), dilation.toDouble())))
        Imgproc.dilate(small, small, kernel)
        Imgproc.medianBlur(small, small, oddAtLeast(smallLong / MEDIAN_DIVISOR, 3))
        val background = mats.new()
        Imgproc.resize(small, background, Size(cols.toDouble(), rows.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        Core.divide(channel, background, channel, 255.0)
    }

    private fun unsharp(channel: Mat, sigma: Double, amount: Double, mats: Mats) {
        val blurred = mats.new()
        Imgproc.GaussianBlur(channel, blurred, Size(0.0, 0.0), sigma)
        Core.addWeighted(channel, 1.0 + amount, blurred, -amount, 0.0, channel)
    }

    private fun equalizeLocally(channel: Mat, clipLimit: Double) {
        Imgproc.createCLAHE(clipLimit, Size(8.0, 8.0)).apply(channel, channel)
    }

    private fun lut(curve: ByteArray, mats: Mats): Mat {
        val table = mats.track(Mat(1, curve.size, CvType.CV_8UC1))
        table.put(0, 0, curve)
        return table
    }

    private fun oddAtLeast(value: Int, minimum: Int): Int {
        val size = max(value, minimum)
        return if (size % 2 == 0) size + 1 else size
    }

    /** Native matrices are not reclaimed in any useful time: every one made here is released on [close]. */
    private class Mats : AutoCloseable {
        private val owned = ArrayList<Mat>()

        fun new(): Mat = track(Mat())

        fun track(mat: Mat): Mat = mat.also { owned += it }

        fun split(source: Mat): List<Mat> {
            val channels = ArrayList<Mat>()
            Core.split(source, channels)
            owned += channels
            return channels
        }

        override fun close() {
            owned.forEach { it.release() }
            owned.clear()
        }
    }

    private companion object {
        const val TAG = "FilterEngine"
        const val MIN_SIDE = 8
        const val BLACK_WHITE_OFFSET = 12.0
        const val BACKGROUND_LONG_EDGE = 512.0
        const val DILATION_DIVISOR = 70
        const val MEDIAN_DIVISOR = 18
        const val SHARPEN_SIGMA_DIVISOR = 700.0
        const val MAGIC_SATURATION = 1.3
        const val BW_SHARP_MIN_THRESHOLD = 150.0
        const val BW_SHARP_MAX_THRESHOLD = 215.0

        val MAGIC_LEVELS: ByteArray by lazy { ToneCurves.toBytes(ToneCurves.levels(black = 24, white = 232, gamma = 1.12)) }
        val LIGHTEN_CURVE: ByteArray by lazy { ToneCurves.toBytes(ToneCurves.gamma(0.72)) }
        val INK_SAVER_CURVE: ByteArray by lazy { ToneCurves.toBytes(ToneCurves.inkSaver()) }
    }
}
