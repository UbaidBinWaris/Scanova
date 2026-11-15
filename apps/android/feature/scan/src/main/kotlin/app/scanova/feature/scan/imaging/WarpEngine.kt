package app.scanova.feature.scan.imaging

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import app.scanova.core.common.log.ScanovaLog
import app.scanova.feature.scan.geometry.Homography
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Perspective warps with OpenCV. Callers fall back to the platform's `Canvas` when this returns null. */
internal object WarpEngine {

    /** [source] warped by [transform] into a new `width × height` bitmap, or null when OpenCV is unavailable or fails. */
    fun warp(source: Bitmap, transform: Homography, width: Int, height: Int): Bitmap? {
        if (!OpenCvSupport.isAvailable) return null
        val input = Mat()
        val output = Mat()
        val matrix = Mat(3, 3, CvType.CV_64FC1)
        return try {
            Utils.bitmapToMat(source, input)
            matrix.put(0, 0, *transform.matrix)
            Imgproc.warpPerspective(
                input,
                output,
                matrix,
                Size(width.toDouble(), height.toDouble()),
                Imgproc.INTER_LINEAR,
                Core.BORDER_REPLICATE,
                Scalar(0.0, 0.0, 0.0, 255.0),
            )
            val result = createBitmap(width, height)
            Utils.matToBitmap(output, result)
            result
        } catch (e: Exception) {
            ScanovaLog.warn(TAG, "opencv_warp_failed_using_canvas", e)
            null
        } finally {
            input.release()
            output.release()
            matrix.release()
        }
    }

    private const val TAG = "WarpEngine"
}
