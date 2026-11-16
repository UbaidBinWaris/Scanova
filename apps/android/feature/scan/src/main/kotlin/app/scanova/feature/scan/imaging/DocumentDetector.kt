package app.scanova.feature.scan.imaging

import android.graphics.Bitmap
import android.os.SystemClock
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
 * Finds the outline of a page in a picture, with two detectors:
 *
 * - the edge detector: OpenCV does the pixel work (blur, edge detection,
 *   contour tracing, polygon simplification) and the pure-Kotlin [QuadScorer]
 *   decides which four-cornered contour is the page. A few milliseconds, exact
 *   to the pixel when it works, but it misses most pages on busy or
 *   low-contrast backgrounds;
 * - the page model ([AiPageDetector], on the phone): finds the page almost
 *   always (SmartDoc 2015: every frame within 2 %), at a tenth to a quarter of a
 *   second.
 *
 * Photos (capture, import, crop) ask both: when the outlines agree, the edge
 * outline is kept for its sharper corners; otherwise the model's. Live frames
 * use the edge detector and ask the model only when that finds nothing, at most
 * every [AI_FRAME_INTERVAL_MILLIS].
 *
 * Every native [Mat] is released before returning; they are not garbage
 * collected in any useful time frame, and this runs several times a second.
 */
internal class DocumentDetector(
    private val pageModel: (Mat) -> Quad? = AiPageDetector::detect,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private var lastModelFrameMillis = Long.MIN_VALUE / 2
    private var lastModelOutline: Quad? = null

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
            detectInGray(gray) ?: modelOnFrame(gray)
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
            val edges = if (scale < 1.0) {
                Imgproc.resize(gray, small, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
                detectInGray(small)
            } else {
                detectInGray(gray)
            }
            choose(edges, modelOnPhoto(rgba))
        } catch (e: Exception) {
            ScanovaLog.warn(TAG, "photo_detection_failed", e)
            null
        } finally {
            rgba.release()
            gray.release()
            small.release()
        }
    }

    /** The edge outline when the model agrees with it (sharper corners), otherwise the model's. */
    private fun choose(edges: Quad?, model: Quad?): Quad? = when {
        model == null -> edges
        edges == null -> model
        edges.maxCornerDistance(model) <= AGREEMENT -> edges
        else -> model
    }

    /** The model on a photo, at the size it was measured on (long side at most [MODEL_LONG_EDGE]). */
    private fun modelOnPhoto(rgba: Mat): Quad? {
        val bgr = Mat()
        val reduced = Mat()
        return try {
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            val scale = MODEL_LONG_EDGE.toDouble() / max(bgr.cols(), bgr.rows())
            if (scale < 1.0) {
                Imgproc.resize(bgr, reduced, Size(bgr.cols() * scale, bgr.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
                pageModel(reduced)
            } else {
                pageModel(bgr)
            }
        } finally {
            bgr.release()
            reduced.release()
        }
    }

    /**
     * The edge detector found nothing in this frame. The model is asked at most
     * every [AI_FRAME_INTERVAL_MILLIS]; in between, its last outline stands for
     * up to [AI_FRAME_HOLD_MILLIS], so the overlay does not flicker. Frames are
     * luminance only: the model reads them as grey (SmartDoc: Jaccard 0.977,
     * against 0.989 in colour, which photos get).
     */
    private fun modelOnFrame(gray: Mat): Quad? {
        val now = clock()
        if (now - lastModelFrameMillis < AI_FRAME_INTERVAL_MILLIS) {
            return lastModelOutline.takeIf { now - lastModelFrameMillis < AI_FRAME_HOLD_MILLIS }
        }
        lastModelFrameMillis = now
        val bgr = Mat()
        return try {
            Imgproc.cvtColor(gray, bgr, Imgproc.COLOR_GRAY2BGR)
            pageModel(bgr).also { lastModelOutline = it }
        } finally {
            bgr.release()
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

        /** The page model's input is made from at most this size, as in its evaluation (1920 × 1080 frames). */
        const val MODEL_LONG_EDGE = 1920

        /** Outlines this close (largest corner gap, as a fraction of the picture) are the same page. */
        const val AGREEMENT = 0.03
        const val AI_FRAME_INTERVAL_MILLIS = 400L
        const val AI_FRAME_HOLD_MILLIS = 800L
    }
}
