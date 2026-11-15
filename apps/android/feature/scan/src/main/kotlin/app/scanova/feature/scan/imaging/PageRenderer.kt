package app.scanova.feature.scan.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import app.scanova.core.common.log.ScanovaLog
import app.scanova.feature.scan.geometry.Homography
import app.scanova.feature.scan.geometry.OutputSize
import app.scanova.feature.scan.geometry.Quad
import app.scanova.feature.scan.session.ScanFilter
import kotlin.math.min
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Turns a photo of a page into the page itself: perspective correction
 * ("flattening") followed by a filter.
 *
 * OpenCV is used when it loaded; every operation has a fallback built on the
 * platform's own `Canvas`, so a phone on which the native library cannot be
 * loaded still produces correctly cropped pages (with simpler filters).
 */
internal class PageRenderer {

    /**
     * Cuts the page outlined by [outline] (fractions of [photo]) out of the
     * photo and straightens it into an upright rectangle with the page's real
     * proportions.
     */
    fun flatten(photo: Bitmap, outline: Quad, maxLongEdge: Int): Bitmap {
        val width = photo.width.toDouble()
        val height = photo.height.toDouble()
        val quad = outline.scale(width, height).clampTo(width, height)
        val size = OutputSize.forQuad(quad, width, height, maxLongEdge)
        val transform = Homography.quadToRectangle(quad, size.width.toDouble(), size.height.toDouble())
        if (transform != null && OpenCvSupport.isAvailable) {
            try {
                return flattenWithOpenCv(photo, transform, size.width, size.height)
            } catch (e: Exception) {
                ScanovaLog.warn(TAG, "opencv_flatten_failed_using_canvas", e)
            }
        }
        return flattenWithCanvas(photo, quad, size.width, size.height)
    }

    /** Returns a new bitmap with [filter] applied; [page] itself is returned for [ScanFilter.ORIGINAL]. */
    fun applyFilter(page: Bitmap, filter: ScanFilter): Bitmap {
        if (filter == ScanFilter.ORIGINAL) return page
        if (OpenCvSupport.isAvailable) {
            try {
                return filterWithOpenCv(page, filter)
            } catch (e: Exception) {
                ScanovaLog.warn(TAG, "opencv_filter_failed_using_canvas", e)
            }
        }
        return filterWithCanvas(page, filter)
    }

    // ── OpenCV ──────────────────────────────────────────────────────────

    private fun flattenWithOpenCv(photo: Bitmap, transform: Homography, width: Int, height: Int): Bitmap {
        val source = Mat()
        val target = Mat()
        val matrix = Mat(3, 3, CvType.CV_64FC1)
        try {
            Utils.bitmapToMat(photo, source)
            matrix.put(0, 0, *transform.matrix)
            Imgproc.warpPerspective(
                source,
                target,
                matrix,
                Size(width.toDouble(), height.toDouble()),
                Imgproc.INTER_LINEAR,
                Core.BORDER_REPLICATE,
                Scalar(0.0, 0.0, 0.0, 255.0),
            )
            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(target, result)
            return result
        } finally {
            source.release()
            target.release()
            matrix.release()
        }
    }

    private fun filterWithOpenCv(page: Bitmap, filter: ScanFilter): Bitmap {
        val rgba = Mat()
        val work = Mat()
        val output = Mat()
        val channels = ArrayList<Mat>()
        try {
            Utils.bitmapToMat(page, rgba)
            when (filter) {
                ScanFilter.ORIGINAL -> rgba.copyTo(output)
                ScanFilter.ENHANCED -> {
                    // Local contrast on lightness only, so colours stay true.
                    Imgproc.cvtColor(rgba, work, Imgproc.COLOR_RGBA2RGB)
                    Imgproc.cvtColor(work, work, Imgproc.COLOR_RGB2Lab)
                    Core.split(work, channels)
                    equalizeLocally(channels[0], clipLimit = 2.0)
                    Core.merge(channels, work)
                    Imgproc.cvtColor(work, work, Imgproc.COLOR_Lab2RGB)
                    Imgproc.cvtColor(work, output, Imgproc.COLOR_RGB2RGBA)
                }
                ScanFilter.GREYSCALE -> {
                    Imgproc.cvtColor(rgba, work, Imgproc.COLOR_RGBA2GRAY)
                    equalizeLocally(work, clipLimit = 1.5)
                    Imgproc.cvtColor(work, output, Imgproc.COLOR_GRAY2RGBA)
                }
                ScanFilter.BLACK_WHITE -> {
                    Imgproc.cvtColor(rgba, work, Imgproc.COLOR_RGBA2GRAY)
                    // The threshold is decided per neighbourhood, so shadows and
                    // uneven lighting do not turn half the page black.
                    val block = oddBlockSize(min(work.cols(), work.rows()))
                    Imgproc.adaptiveThreshold(
                        work,
                        work,
                        255.0,
                        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                        Imgproc.THRESH_BINARY,
                        block,
                        BLACK_WHITE_OFFSET,
                    )
                    Imgproc.medianBlur(work, work, 3)
                    Imgproc.cvtColor(work, output, Imgproc.COLOR_GRAY2RGBA)
                }
            }
            val result = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(output, result)
            return result
        } finally {
            channels.forEach { it.release() }
            rgba.release()
            work.release()
            output.release()
        }
    }

    private fun equalizeLocally(channel: Mat, clipLimit: Double) {
        val clahe = Imgproc.createCLAHE(clipLimit, Size(8.0, 8.0))
        clahe.apply(channel, channel)
    }

    /** Neighbourhood for the black-and-white threshold: about 1/16 of the short side, odd, bounded. */
    private fun oddBlockSize(shortSide: Int): Int {
        val size = (shortSide / 16).coerceIn(15, 151)
        return if (size % 2 == 0) size + 1 else size
    }

    // ── Platform fallback ───────────────────────────────────────────────

    private fun flattenWithCanvas(photo: Bitmap, quad: Quad, width: Int, height: Int): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val source = floatArrayOf(
            quad.topLeft.x.toFloat(), quad.topLeft.y.toFloat(),
            quad.topRight.x.toFloat(), quad.topRight.y.toFloat(),
            quad.bottomRight.x.toFloat(), quad.bottomRight.y.toFloat(),
            quad.bottomLeft.x.toFloat(), quad.bottomLeft.y.toFloat(),
        )
        val target = floatArrayOf(
            0f, 0f,
            width.toFloat(), 0f,
            width.toFloat(), height.toFloat(),
            0f, height.toFloat(),
        )
        val matrix = Matrix()
        // Four point pairs define a perspective transform, exactly like the homography.
        if (!matrix.setPolyToPoly(source, 0, target, 0, 4)) {
            matrix.setRectToRect(
                android.graphics.RectF(0f, 0f, photo.width.toFloat(), photo.height.toFloat()),
                android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat()),
                Matrix.ScaleToFit.FILL,
            )
        }
        Canvas(result).drawBitmap(photo, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return result
    }

    private fun filterWithCanvas(page: Bitmap, filter: ScanFilter): Bitmap {
        val matrix = when (filter) {
            ScanFilter.ORIGINAL -> return page
            ScanFilter.ENHANCED -> contrast(1.2f).apply { postConcat(ColorMatrix().apply { setSaturation(1.1f) }) }
            ScanFilter.GREYSCALE -> ColorMatrix().apply { setSaturation(0f) }.apply { postConcat(contrast(1.15f)) }
            // A steep contrast curve pushes paper to white and ink to black.
            ScanFilter.BLACK_WHITE -> ColorMatrix().apply { setSaturation(0f) }.apply { postConcat(contrast(4f)) }
        }
        val result = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
        Canvas(result).drawBitmap(page, 0f, 0f, paint)
        return result
    }

    /** Scales colour values around mid-grey. */
    private fun contrast(factor: Float): ColorMatrix {
        val shift = 127.5f * (1f - factor)
        return ColorMatrix(
            floatArrayOf(
                factor, 0f, 0f, 0f, shift,
                0f, factor, 0f, 0f, shift,
                0f, 0f, factor, 0f, shift,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    }

    private companion object {
        const val TAG = "PageRenderer"
        const val BLACK_WHITE_OFFSET = 12.0
    }
}
