package app.scanova.feature.scan.imaging

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.data.model.PageMesh
import app.scanova.feature.scan.render.MeshGeometry
import app.scanova.feature.scan.render.RecipeGeometry
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Flattens a curled page along its [PageMesh] with OpenCV's `remap`, at full
 * resolution. Callers fall back to the crop outline when this returns null.
 */
internal object MeshWarpEngine {
    private const val TAG = "MeshWarpEngine"

    /**
     * [source] (the original as stored: [sourceRotation] clockwise makes it
     * upright, the mesh is in fractions of the upright image) flattened along
     * [mesh], at most [maxLongEdge] on the page's longer side, then turned
     * [rotation] degrees clockwise. Null when OpenCV is unavailable or fails.
     */
    fun warp(source: Bitmap, mesh: PageMesh, sourceRotation: Int, rotation: Int, maxLongEdge: Int): Bitmap? {
        if (!OpenCvSupport.isAvailable) return null
        val upright = RecipeGeometry.turnedSize(source.width, source.height, sourceRotation)
        if (!MeshGeometry.isUsable(mesh, upright.width.toDouble(), upright.height.toDouble())) return null
        val size = MeshGeometry.outputSize(mesh, upright.width, upright.height, maxLongEdge)
        val (xs, ys) = MeshGeometry.toRaw(mesh, sourceRotation)
        val input = Mat()
        val mapX = Mat()
        val mapY = Mat()
        val flat = Mat()
        val turned = Mat()
        return try {
            // Allocated inside the try, so a failed native allocation falls back to the outline too.
            mapX.create(size.height, size.width, CvType.CV_32FC1)
            mapY.create(size.height, size.width, CvType.CV_32FC1)
            MeshGeometry.forEachMapRow(xs, ys, mesh.rows, mesh.cols, size.width, size.height, source.width, source.height) { y, rowX, rowY ->
                mapX.put(y, 0, rowX)
                mapY.put(y, 0, rowY)
            }
            Utils.bitmapToMat(source, input)
            Imgproc.remap(input, flat, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE, Scalar(0.0, 0.0, 0.0, 255.0))
            input.release()
            mapX.release()
            mapY.release()
            val result = when (RecipeGeometry.quarterTurns(rotation)) {
                1 -> turned.also { Core.rotate(flat, it, Core.ROTATE_90_CLOCKWISE) }
                2 -> turned.also { Core.rotate(flat, it, Core.ROTATE_180) }
                3 -> turned.also { Core.rotate(flat, it, Core.ROTATE_90_COUNTERCLOCKWISE) }
                else -> flat
            }
            createBitmap(result.cols(), result.rows()).also { Utils.matToBitmap(result, it) }
        } catch (e: Exception) {
            ScanovaLog.warn(TAG, "mesh_warp_failed_using_outline", e)
            null
        } finally {
            input.release()
            mapX.release()
            mapY.release()
            flat.release()
            turned.release()
        }
    }
}
