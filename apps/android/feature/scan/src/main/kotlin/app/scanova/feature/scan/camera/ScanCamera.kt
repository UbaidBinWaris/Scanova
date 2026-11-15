package app.scanova.feature.scan.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.data.settings.FlashMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** A photo as the camera delivered it: JPEG bytes that still need turning upright. */
class CapturedPhoto(
    val jpeg: ByteArray,
    /** Clockwise rotation that makes the image upright. */
    val rotationDegrees: Int,
)

/**
 * Owns the CameraX session of the scan screen: a preview, a low-resolution
 * stream for live edge detection and a still capture.
 *
 * All three use the sensor's native 4:3 shape, so they see the same field of
 * view and an outline found in an analysis frame lines up with the preview.
 * Photos are delivered in memory; nothing is written to storage by the camera.
 *
 * @param hd capture at the sensor's full resolution; otherwise a balanced
 *   size (about 8 MP) that is faster and smaller and still sharp for documents.
 */
internal class ScanCamera(
    private val context: Context,
    private val analyzer: ImageAnalysis.Analyzer,
    private val hd: Boolean,
) {
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var released = false
    private var flash: FlashMode = FlashMode.OFF

    /**
     * Starts the camera and attaches it to [owner]'s lifecycle.
     * [onReady] reports whether the phone has a torch; [onError] is called when the camera cannot start.
     */
    fun start(owner: LifecycleOwner, previewView: PreviewView, onReady: (hasTorch: Boolean) -> Unit, onError: () -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (released) return@addListener
                try {
                    val cameraProvider = future.get()
                    provider = cameraProvider

                    val fourByThree = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                    val preview = Preview.Builder()
                        .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(fourByThree).build())
                        .build()
                        .also { it.surfaceProvider = previewView.surfaceProvider }

                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(fourByThree)
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        ANALYSIS_SIZE,
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                    ),
                                )
                                .build(),
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, analyzer) }

                    val captureResolution = if (hd) {
                        ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
                    } else {
                        ResolutionStrategy(BALANCED_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(
                            if (hd) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY,
                        )
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(fourByThree)
                                .setResolutionStrategy(captureResolution)
                                .build(),
                        )
                        .build()

                    cameraProvider.unbindAll()
                    val bound = cameraProvider.bindToLifecycle(
                        owner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                        capture,
                    )
                    camera = bound
                    imageCapture = capture
                    applyFlash()
                    onReady(bound.cameraInfo.hasFlashUnit())
                } catch (e: Exception) {
                    // No back camera, camera in use by another app, disabled by device policy…
                    ScanovaLog.warn(TAG, "scan_camera_start_failed", e)
                    onError()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /** Flash for photos (off, auto, on) or a steady torch. Applied now and kept across restarts of the session. */
    fun setFlash(mode: FlashMode) {
        flash = mode
        applyFlash()
    }

    private fun applyFlash() {
        val capture = imageCapture ?: return
        runCatching {
            capture.flashMode = when (flash) {
                FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
                FlashMode.ON -> ImageCapture.FLASH_MODE_ON
                FlashMode.OFF, FlashMode.TORCH -> ImageCapture.FLASH_MODE_OFF
            }
            camera?.cameraControl?.enableTorch(flash == FlashMode.TORCH)
        }
    }

    /** Takes a full-resolution photo. Returns null when the capture failed. */
    suspend fun takePicture(): CapturedPhoto? = suspendCancellableCoroutine { continuation ->
        val capture = imageCapture
        if (capture == null || released) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val photo = try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        CapturedPhoto(bytes, image.imageInfo.rotationDegrees)
                    } catch (e: Exception) {
                        ScanovaLog.warn(TAG, "capture_read_failed", e)
                        null
                    } finally {
                        image.close()
                    }
                    if (continuation.isActive) continuation.resume(photo)
                }

                override fun onError(exception: ImageCaptureException) {
                    ScanovaLog.warn(TAG, "capture_failed code=${exception.imageCaptureError}")
                    if (continuation.isActive) continuation.resume(null)
                }
            },
        )
    }

    fun release() {
        released = true
        runCatching { provider?.unbindAll() }
        analysisExecutor.shutdown()
        camera = null
        imageCapture = null
    }

    private companion object {
        const val TAG = "ScanCamera"

        /** Edge detection needs shape, not detail; small frames keep it fast and cool. */
        val ANALYSIS_SIZE = Size(640, 480)

        /** The balanced (non-HD) photo size: 8 MP, plenty for an A4 page at 300 dpi. */
        val BALANCED_SIZE = Size(3264, 2448)
    }
}
