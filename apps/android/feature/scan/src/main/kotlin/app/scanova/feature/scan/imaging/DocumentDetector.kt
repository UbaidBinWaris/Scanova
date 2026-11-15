package app.scanova.feature.scan.imaging

import android.graphics.Bitmap
import app.scanova.core.common.log.ScanovaLog
import app.scanova.feature.scan.geometry.Point2
import app.scanova.feature.scan.geometry.Quad
import app.scanova.feature.scan.geometry.QuadScorer
import kotlin.math.max
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc

/**
 * Finds the outline of a page in a picture.
 *
 * OpenCV does the pixel work (blur, edge detection, contour tracing, polygon
 * simplification). Deciding which four-cornered contour is the page is left to
 * the pure-Kotlin [QuadScorer], which is unit-tested.
 *
 * Every native [Mat] is released before returning; they are not garbage
 * collected in any useful time frame, and this runs several times a second.
 */
internal class DocumentDetector {

    /**
     * Detects a page in a greyscale frame given as tightly packed rows
     * ([width] × [height] bytes). Returns the outline as fractions of the
     * frame, or null when no convincing page is visible.
     */
    fun detect(luminance: ByteArray, width: Int, height: Int): Quad? {
        if (!OpenCvSupport.isAvailable || width <= 0 || height <= 0 || luminance.size < width * height) return null
        val gray = Mat(height, width, CvType.CV_8UC1)
        return try {
            gray.put(0, 0, luminance)
            detectInGray(gray)
        } catch (e: Exception) {
            ScanovaLog.warn(TAG, "frame_detection_failed", e)
            null
        } finally {
            gray.release()
        }
    }

    /** Detects a page in a photo. The photo is analysed at reduced size; the result is in fractions. */
    fun detect(photo: Bitmap): Quad? {
        if (!OpenCvSupport.isAvailable || photo.width <= 0 || photo.height <= 0) return null
        val rgba = Mat()
        val gray = Mat()
        val small = Mat()
        return try {
            Utils.bitmapToMat(photo, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val scale = ANALYSIS_LONG_EDGE.toDouble() / max(gray.cols(), gray.rows())
            if (scale < 1.0) {
                Imgproc.resize(gray, small, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
                detectInGray(small)
            } else {
                detectInGray(gray)
            }
        } catch (e: Exception) {
            ScanovaLog.warn(TAG, "photo_detection_failed", e)
            null
        } finally {
            rgba.release()
            gray.release()
            small.release()
        }
    }

    private fun detectInGray(gray: Mat): Quad? {
        val width = gray.cols().toDouble()
        val height = gray.rows().toDouble()
        val blurred = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val mean = MatOfDouble()
        val deviation = MatOfDouble()
        val contours = ArrayList<MatOfPoint>()
        try {
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            // Thresholds follow the frame's brightness so detection works in dim rooms and in sunlight.
            Core.meanStdDev(blurred, mean, deviation)
            val brightness = mean.toArray().firstOrNull() ?: 128.0
            val lower = (0.5 * brightness).coerceIn(15.0, 90.0)
            Imgproc.Canny(blurred, edges, lower, lower * 3.0)
            // Close small gaps so the page border becomes one closed contour.
            Imgproc.dilate(edges, edges, kernel)
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            var best: Quad? = null
            var bestScore = MIN_SCORE
            val largest = contours.sortedByDescending { Geometry.contourArea(it) }.take(MAX_CANDIDATES)
            for (contour in largest) {
                val candidate = approximateQuad(contour) ?: continue
                val score = QuadScorer.score(candidate, width, height)
                if (score > bestScore) {
                    bestScore = score
                    best = candidate
                }
            }
            return best?.scale(1.0 / width, 1.0 / height)
        } finally {
            contours.forEach { it.release() }
            blurred.release()
            edges.release()
            hierarchy.release()
            kernel.release()
            mean.release()
            deviation.release()
        }
    }

    /** Simplifies a contour to a polygon and returns it when it has exactly four corners. */
    private fun approximateQuad(contour: MatOfPoint): Quad? {
        val curve = MatOfPoint2f(*contour.toArray())
        val polygon = MatOfPoint2f()
        return try {
            val perimeter = Geometry.arcLength(curve, true)
            Geometry.approxPolyDP(curve, polygon, 0.02 * perimeter, true)
            val points = polygon.toArray()
            if (points.size == 4) Quad.fromUnordered(points.map { Point2(it.x, it.y) }) else null
        } finally {
            curve.release()
            polygon.release()
        }
    }

    private companion object {
        const val TAG = "Detector"
        const val ANALYSIS_LONG_EDGE = 640
        const val MAX_CANDIDATES = 8
        const val MIN_SCORE = 0.25
    }
}
