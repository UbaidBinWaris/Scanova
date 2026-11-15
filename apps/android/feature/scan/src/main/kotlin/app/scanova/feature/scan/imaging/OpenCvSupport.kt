package app.scanova.feature.scan.imaging

import app.scanova.core.common.log.ScanovaLog
import org.opencv.android.OpenCVLoader

/**
 * Loads OpenCV's native library once. If loading fails (an unsupported CPU
 * architecture, a damaged install) the app keeps working: edge detection is
 * simply unavailable and cropping and filters fall back to the platform's own
 * graphics code.
 */
internal object OpenCvSupport {
    val isAvailable: Boolean by lazy {
        try {
            OpenCVLoader.initLocal()
        } catch (e: Throwable) {
            // UnsatisfiedLinkError is an Error, not an Exception.
            ScanovaLog.error(TAG, "opencv_load_failed", e)
            false
        }
    }

    private const val TAG = "OpenCv"
}
