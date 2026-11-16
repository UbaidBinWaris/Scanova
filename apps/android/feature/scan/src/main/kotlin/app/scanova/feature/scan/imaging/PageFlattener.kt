package app.scanova.feature.scan.imaging

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.data.model.PageMesh
import app.scanova.feature.scan.geometry.Quad
import app.scanova.feature.scan.render.MeshGeometry
import kotlin.math.max
import kotlin.math.min
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc

/**
 * Flattens curled pages with a neural network: UVDoc (Verhoeven et al.,
 * SIGGRAPH Asia 2023; MIT, Tanguy Magne), exported from the official weights to
 * a 16 MB model that gives the page as a 45 × 31 mesh. On Book100's real
 * photos of curved book pages it lifts MS-SSIM from 0.270 to 0.454
 * (docs/ai-image-research.md).
 *
 * The model reads a box around the page with a 5 % margin, so a curved page's
 * bulging edges and the background it curves against are in view. The mesh
 * goes into the page's recipe ([app.scanova.core.data.model.PageEdit.flatten]),
 * so rendering never needs the model again. Runs on the phone through OpenCV's
 * dnn module; nothing leaves the device. One network for the whole process,
 * used by one caller at a time.
 *
 * Keeps only the application context (see install), which lives as long as the
 * process: the static-field-leak warning does not apply.
 */
@SuppressLint("StaticFieldLeak")
internal object PageFlattener {
    private const val TAG = "PageFlattener"
    const val ASSET = "models/uvdoc-mesh.onnx"
    private const val INPUT_WIDTH = 488.0
    private const val INPUT_HEIGHT = 712.0
    const val ROWS = 45
    const val COLS = 31
    private const val MARGIN = 0.05
    private const val MIN_BOX = 32

    /** The model's [mesh] for a page and how bent it found the page ([MeshGeometry.bend]). */
    class Result(val mesh: PageMesh, val bend: Double)

    @Volatile private var context: Context? = null
    private val lock = Any()
    private var net: Net? = null

    @Volatile private var unavailable = false

    /** Called once at app start (ScanModelsInitializer); the model itself loads on first use. */
    fun install(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * The model can be asked: OpenCV is there, the app started it, and it has
     * not failed to load. Cheap, so callers check it before decoding a photo
     * for nothing, and "Flatten" is not offered where it cannot work.
     */
    val canRun: Boolean get() = !unavailable && context != null && OpenCvSupport.isAvailable

    /**
     * The mesh of the page inside [outline] (fractions of [upright]; null for
     * the whole photo) in [upright], an upright photo of any size, with its
     * mesh in fractions of that photo. Null when the model is unavailable or
     * fails; the page then stays flattened by its outline.
     */
    fun flatten(upright: Bitmap, outline: Quad?): Result? {
        val box = looseBox(upright.width, upright.height, outline)
        return synchronized(lock) {
            val model = model() ?: return@synchronized null
            val rgba = Mat()
            var page: Mat? = null
            val rgb = Mat()
            val input = Mat()
            var blob: Mat? = null
            val outputs = ArrayList<Mat>()
            try {
                Utils.bitmapToMat(upright, rgba)
                page = rgba.submat(box)
                Imgproc.cvtColor(page, rgb, Imgproc.COLOR_RGBA2RGB)
                // Exactly UVDoc's own input: RGB scaled to 0..1, then a bilinear resize to 488 × 712.
                rgb.convertTo(input, CvType.CV_32F, 1.0 / 255.0)
                blob = Dnn.blobFromImage(input, 1.0, Size(INPUT_WIDTH, INPUT_HEIGHT), Scalar(0.0, 0.0, 0.0), false, false)
                model.setInput(blob)
                // OpenCV's JNI calls ArrayList methods on both lists: listOf() would abort the process.
                model.forward(outputs, arrayListOf("mesh"))
                val grid = FloatArray(2 * ROWS * COLS)
                outputs.single().reshape(1, 1).get(0, 0, grid)
                val mesh = toMesh(grid, box, upright.width, upright.height)
                val width = upright.width.toDouble()
                val height = upright.height.toDouble()
                // A mesh collapsed onto a sliver of the photo is no page: keep the outline instead.
                if (!MeshGeometry.isUsable(mesh, width, height)) return@synchronized null
                Result(mesh, MeshGeometry.bend(mesh, width, height)).takeIf { it.bend.isFinite() }
            } catch (e: Exception) {
                ScanovaLog.warn(TAG, "inference_failed", e)
                null
            } finally {
                page?.release()
                rgba.release()
                rgb.release()
                input.release()
                blob?.release()
                outputs.forEach(Mat::release)
            }
        }
    }

    /** The axis-aligned box around [outline] widened by 5 % of its size, in pixels; the whole photo when too small. */
    fun looseBox(width: Int, height: Int, outline: Quad?): Rect {
        if (outline == null) return Rect(0, 0, width, height)
        val xs = outline.corners.map { it.x * width }
        val ys = outline.corners.map { it.y * height }
        val left = xs.min()
        val right = xs.max()
        val top = ys.min()
        val bottom = ys.max()
        val marginX = (right - left) * MARGIN
        val marginY = (bottom - top) * MARGIN
        val x0 = max(0, (left - marginX).toInt())
        val y0 = max(0, (top - marginY).toInt())
        val x1 = min(width, (right + marginX).toInt())
        val y1 = min(height, (bottom + marginY).toInt())
        if (x1 - x0 <= MIN_BOX || y1 - y0 <= MIN_BOX) return Rect(0, 0, width, height)
        return Rect(x0, y0, x1 - x0, y1 - y0)
    }

    /**
     * The model's grid (x plane then y plane, each 45 × 31, in −1..1 of the box
     * with its corners on the box's corner pixels) as a mesh in fractions of
     * the whole [width] × [height] photo.
     */
    fun toMesh(grid: FloatArray, box: Rect, width: Int, height: Int): PageMesh {
        val count = ROWS * COLS
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        for (i in 0 until count) {
            xs[i] = ((box.x + (grid[i] + 1f) / 2f * (box.width - 1)) / width).toFloat()
            ys[i] = ((box.y + (grid[count + i] + 1f) / 2f * (box.height - 1)) / height).toFloat()
        }
        return PageMesh.of(ROWS, COLS, xs, ys)
    }

    private fun model(): Net? {
        net?.let { return it }
        if (unavailable || !OpenCvSupport.isAvailable) return null
        val appContext = context ?: return null
        return try {
            readOnnxAsset(appContext, ASSET).also {
                check(!it.empty()) { "empty_network" }
                net = it
            }
        } catch (e: OutOfMemoryError) {
            // Short of memory right now: the page keeps its outline, a later page tries again.
            ScanovaLog.warn(TAG, "model_load_out_of_memory")
            null
        } catch (e: Exception) {
            // Pages stay flattened by their outline; the model is not asked again.
            unavailable = true
            ScanovaLog.error(TAG, "model_load_failed", e)
            null
        }
    }
}
