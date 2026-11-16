package app.scanova.feature.scan.filter

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Native matrices are not reclaimed in any useful time: every one made here is
 * released on [close]. One instance per filter run.
 */
internal class Mats : AutoCloseable {
    private val owned = ArrayList<Mat>()

    fun new(): Mat = track(Mat())

    fun track(mat: Mat): Mat = mat.also { owned += it }

    fun split(source: Mat): List<Mat> {
        val channels = ArrayList<Mat>()
        Core.split(source, channels)
        owned += channels
        return channels
    }

    /** A copy of [source] whose longer side is at most [longEdge] (area-averaged, so no aliasing). */
    fun smallOf(source: Mat, longEdge: Int): Mat {
        val scale = min(1.0, longEdge.toDouble() / max(source.cols(), source.rows()))
        val small = new()
        if (scale >= 1.0) {
            source.copyTo(small)
        } else {
            val size = Size(
                max(1, (source.cols() * scale).roundToInt()).toDouble(),
                max(1, (source.rows() * scale).roundToInt()).toDouble(),
            )
            Imgproc.resize(source, small, size, 0.0, 0.0, Imgproc.INTER_AREA)
        }
        return small
    }

    /** [source] resized to [like]'s size (smooth maps only: they come from small copies). */
    fun upTo(source: Mat, like: Mat, interpolation: Int = Imgproc.INTER_CUBIC): Mat {
        val out = new()
        Imgproc.resize(source, out, like.size(), 0.0, 0.0, interpolation)
        return out
    }

    fun ellipse(size: Int): Mat =
        track(Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(size.toDouble(), size.toDouble())))

    /** The floats of a single-channel CV_32F matrix, row by row. */
    fun floats(mat: Mat): FloatArray {
        require(mat.type() == CvType.CV_32FC1) { "expected CV_32FC1" }
        val values = FloatArray(mat.rows() * mat.cols())
        mat.get(0, 0, values)
        return values
    }

    /** A single-channel CV_32F matrix of [rows] × [cols] holding [values]. */
    fun ofFloats(values: FloatArray, rows: Int, cols: Int): Mat {
        val mat = track(Mat(rows, cols, CvType.CV_32FC1))
        mat.put(0, 0, values)
        return mat
    }

    override fun close() {
        owned.forEach { it.release() }
        owned.clear()
    }

    companion object {
        /** The smallest odd number ≥ both [value] and [minimum] (kernel sizes must be odd). */
        fun odd(value: Int, minimum: Int = 3): Int {
            val size = max(value, minimum)
            return if (size % 2 == 0) size + 1 else size
        }
    }
}
