package app.scanova.feature.scan.imaging

import android.content.Context
import app.scanova.core.common.log.ScanovaLog
import app.scanova.feature.scan.geometry.Quad
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net

/**
 * Finds the page with a small neural network: DocAligner lcnet100 (DocsaidLab,
 * Apache-2.0, 4.8 MB, bundled in the app). On SmartDoc 2015's 1,180 real phone
 * frames it places every page within 2 % of the photo diagonal, cluttered desks
 * included, where the edge-based detector finds one page in five
 * (docs/ai-image-research.md).
 *
 * Runs on the phone through OpenCV's dnn module, which the app already ships;
 * nothing leaves the device. About a tenth to a quarter of a second per photo, so
 * the live camera calls it only when the fast edge detector finds nothing.
 * One network for the whole process, used by one caller at a time.
 */
internal object AiPageDetector {
    private const val TAG = "AiPageDetector"
    const val ASSET = "models/docaligner-lcnet100.onnx"
    private const val INPUT = 256.0
    private const val MAP = 128

    @Volatile private var context: Context? = null
    private val lock = Any()
    private var net: Net? = null
    private var unavailable = false

    /** Called once at app start (ScanModelsInitializer); the model itself loads on first use. */
    fun install(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * The page outline in [bgr] (8-bit, 3 channels, any size), as fractions of
     * the image, or null when there is no page or the model is unavailable.
     */
    fun detect(bgr: Mat): Quad? {
        if (bgr.empty() || bgr.type() != CvType.CV_8UC3) return null
        return synchronized(lock) {
            val model = model() ?: return@synchronized null
            // Exactly the model's training input: BGR scaled to 0..1, bilinear 256 × 256.
            val blob = Dnn.blobFromImage(bgr, 1.0 / 255.0, Size(INPUT, INPUT), Scalar(0.0, 0.0, 0.0), false, false)
            val output = Mat()
            try {
                model.setInput(blob)
                model.forward().copyTo(output)
                val maps = FloatArray(4 * MAP * MAP)
                output.reshape(1, 1).get(0, 0, maps)
                HeatmapDecoder.decode(maps, count = 4, size = MAP)?.let(Quad::fromUnordered)
            } catch (e: Exception) {
                ScanovaLog.warn(TAG, "inference_failed", e)
                null
            } finally {
                blob.release()
                output.release()
            }
        }
    }

    private fun model(): Net? {
        net?.let { return it }
        if (unavailable || !OpenCvSupport.isAvailable) return null
        val appContext = context ?: return null
        return try {
            val bytes = appContext.assets.open(ASSET).use { it.readBytes() }
            val buffer = MatOfByte(*bytes)
            Dnn.readNetFromONNX(buffer).also {
                buffer.release()
                check(!it.empty()) { "empty_network" }
                net = it
            }
        } catch (e: Exception) {
            // The edge detector keeps working; the page model is not asked again.
            unavailable = true
            ScanovaLog.error(TAG, "model_load_failed", e)
            null
        }
    }
}
