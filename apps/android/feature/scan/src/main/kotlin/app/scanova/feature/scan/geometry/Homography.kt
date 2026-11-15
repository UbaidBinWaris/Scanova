package app.scanova.feature.scan.geometry

import kotlin.math.abs

/**
 * The projective transform that flattens a page: it maps the four corners of
 * the outline in the photo onto the corners of an upright rectangle.
 *
 * The 3×3 matrix is stored row by row; `matrix[8]` is always 1.
 */
class Homography private constructor(val matrix: DoubleArray) {

    fun map(point: Point2): Point2 {
        val w = matrix[6] * point.x + matrix[7] * point.y + matrix[8]
        return Point2(
            (matrix[0] * point.x + matrix[1] * point.y + matrix[2]) / w,
            (matrix[3] * point.x + matrix[4] * point.y + matrix[5]) / w,
        )
    }

    companion object {
        /** Maps [quad] onto a `width × height` rectangle with its top-left corner at the origin. */
        fun quadToRectangle(quad: Quad, width: Double, height: Double): Homography? =
            between(quad, Quad(Point2(0.0, 0.0), Point2(width, 0.0), Point2(width, height), Point2(0.0, height)))

        /** The transform taking each corner of [from] to the same-named corner of [to]. */
        fun between(from: Quad, to: Quad): Homography? {
            // Eight unknowns h0..h7 (h8 = 1), two equations per corner pair:
            //   x' = (h0 x + h1 y + h2) / (h6 x + h7 y + 1)
            //   y' = (h3 x + h4 y + h5) / (h6 x + h7 y + 1)
            val a = Array(8) { DoubleArray(9) }
            from.corners.zip(to.corners).forEachIndexed { index, (source, target) ->
                val row = index * 2
                a[row][0] = source.x
                a[row][1] = source.y
                a[row][2] = 1.0
                a[row][6] = -target.x * source.x
                a[row][7] = -target.x * source.y
                a[row][8] = target.x
                a[row + 1][3] = source.x
                a[row + 1][4] = source.y
                a[row + 1][5] = 1.0
                a[row + 1][6] = -target.y * source.x
                a[row + 1][7] = -target.y * source.y
                a[row + 1][8] = target.y
            }
            val solution = solve(a) ?: return null
            return Homography(solution + 1.0)
        }

        /** Gauss–Jordan elimination with partial pivoting on an 8×9 augmented matrix. */
        private fun solve(a: Array<DoubleArray>): DoubleArray? {
            val n = a.size
            for (column in 0 until n) {
                var pivot = column
                for (row in column + 1 until n) {
                    if (abs(a[row][column]) > abs(a[pivot][column])) pivot = row
                }
                if (abs(a[pivot][column]) < 1e-12) return null
                val swap = a[column]
                a[column] = a[pivot]
                a[pivot] = swap
                val divisor = a[column][column]
                for (k in column..n) a[column][k] /= divisor
                for (row in 0 until n) {
                    if (row == column) continue
                    val factor = a[row][column]
                    if (factor == 0.0) continue
                    for (k in column..n) a[row][k] -= factor * a[column][k]
                }
            }
            return DoubleArray(n) { a[it][n] }
        }
    }
}
