package app.scanova.feature.scan.camera

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import app.scanova.core.common.log.ScanovaLog
import app.scanova.feature.scan.geometry.FrameMapping
import app.scanova.feature.scan.geometry.Quad
import app.scanova.feature.scan.imaging.DocumentDetector

/** What one analysed camera frame showed. */
internal class FrameResult(
    /** The page outline as fractions of the upright frame, or null when no page was found. */
    val outline: Quad?,
    /** Width divided by height of the frame once turned upright. */
    val uprightAspect: Float,
    val timeMillis: Long,
    /** Glare burns part of the page white: the hint asks to tilt the phone (smoothed over frames, [GlareMeter]). */
    val glare: Boolean = false,
    /** This frame alone shows a glare spot ([GlareMeter.inFrame]): auto-capture must not take it. */
    val glareInFrame: Boolean = false,
)

/**
 * Looks for a page in the live camera stream. Runs on the camera's analysis
 * thread; frames arriving faster than [MIN_INTERVAL_MILLIS] are skipped, which
 * is plenty for a smooth overlay and keeps the phone cool.
 */
internal class FrameAnalyzer(
    private val detector: DocumentDetector,
    private val onResult: (FrameResult) -> Unit,
) : ImageAnalysis.Analyzer {

    private var lastAnalysisMillis = 0L
    private val glareMeter = GlareMeter()
    private var luminance = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalysisMillis < MIN_INTERVAL_MILLIS) return
            lastAnalysisMillis = now

            val width = image.width
            val height = image.height
            if (width <= 0 || height <= 0) return
            copyLuminance(image, width, height)

            val rotation = image.imageInfo.rotationDegrees
            val sideways = rotation == 90 || rotation == 270
            val uprightAspect = if (sideways) height.toFloat() / width else width.toFloat() / height

            // The detector works on the frame as the sensor delivers it; the
            // result is turned upright and its corners are named again.
            val found = detector.detect(luminance, width, height)
            val glare = glareMeter.onFrame(luminance, width, height, found)
            val outline = found
                ?.corners
                ?.map { FrameMapping.rotateNormalized(it, rotation) }
                ?.let(Quad::fromUnordered)

            onResult(FrameResult(outline, uprightAspect, now, glare, glareMeter.inFrame))
        } catch (e: Exception) {
            ScanovaLog.warn(TAG, "frame_analysis_failed", e)
        } finally {
            // Always release the frame, or the camera stops delivering new ones.
            image.close()
        }
    }

    /** Copies the brightness plane into [luminance] as tightly packed rows. */
    private fun copyLuminance(image: ImageProxy, width: Int, height: Int) {
        val size = width * height
        if (luminance.size != size) luminance = ByteArray(size)
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        if (rowStride == width) {
            buffer.position(0)
            buffer.get(luminance, 0, minOf(size, buffer.remaining()))
            return
        }
        // Rows are padded: copy them one by one.
        for (row in 0 until height) {
            val start = row * rowStride
            if (start + width > buffer.limit()) break
            buffer.position(start)
            buffer.get(luminance, row * width, width)
        }
    }

    private companion object {
        const val TAG = "FrameAnalyzer"
        const val MIN_INTERVAL_MILLIS = 100L
    }
}
