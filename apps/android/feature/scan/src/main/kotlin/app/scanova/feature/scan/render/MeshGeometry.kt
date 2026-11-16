package app.scanova.feature.scan.render

import app.scanova.core.data.model.PageMesh
import app.scanova.feature.scan.geometry.AspectRatioEstimator
import app.scanova.feature.scan.geometry.OutputSize
import app.scanova.feature.scan.geometry.PixelSize
import app.scanova.feature.scan.geometry.Point2
import app.scanova.feature.scan.geometry.Quad
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The geometry of a curled page's [PageMesh]: how big the flattened page is,
 * which pixel of the photo each pixel of the page shows, and how far the page
 * is from flat ([bend]).
 *
 * The mesh's corner points sit on the page's corners: point (r, c) is the
 * page at fraction (c / (cols − 1), r / (rows − 1)) of its width and height,
 * and between points the page is bilinear, as in UVDoc's own unwarping
 * (`align_corners = true`).
 *
 * Pure Kotlin, unit-tested; the renderer hands the maps to OpenCV's `remap`.
 */
internal object MeshGeometry {

    /**
     * The mesh outlines a real page in a [width] × [height] image: each of its
     * four edges is at least 2 % of the image's longer side, and its corners
     * make a convex outline the way a photographed page does (top-left,
     * top-right, bottom-right, bottom-left clockwise on screen: not mirrored).
     * A collapsed, twisted or mirrored mesh (from a damaged recipe, or a model
     * that saw no page) is not used: the page keeps its crop outline. Measured
     * in fractions, so a preview and the full render agree.
     */
    fun isUsable(mesh: PageMesh, width: Double, height: Double): Boolean {
        if (!mesh.isValid || width <= 0.0 || height <= 0.0) return false
        val (across, down) = edgeLengths(mesh.xs, mesh.ys, mesh.rows, mesh.cols, width, height)
        val shortest = minOf(across.first, across.second, down.first, down.second)
        if (!(shortest >= MIN_EDGE_FRACTION * max(width, height))) return false
        val corners = cornerQuad(mesh, width, height)
        return corners.isConvex() && signedArea(corners) > 0.0
    }

    /** Shoelace area of [quad] in image coordinates (y down): positive when its corners run clockwise on screen. */
    private fun signedArea(quad: Quad): Double {
        val points = quad.corners
        var twice = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            twice += a.x * b.y - b.x * a.y
        }
        return twice / 2
    }

    /**
     * Pixel size of the flattened page: the resolution its edges had in the
     * photo (measured along the curve), at most [maxLongEdge] on its longer
     * side. Its proportions are estimated from the mesh's corners as for a
     * cropped page ([AspectRatioEstimator]): on Book100's curved pages that is
     * within 4.9 % of the true page for half of them, about 3.5 % too narrow on
     * average (docs/ai-image-research.md). [uprightWidth] × [uprightHeight] is
     * the upright original the mesh's fractions refer to.
     */
    fun outputSize(mesh: PageMesh, uprightWidth: Int, uprightHeight: Int, maxLongEdge: Int): PixelSize {
        val w = uprightWidth.toDouble()
        val h = uprightHeight.toDouble()
        val (width, height) = edgeLengths(mesh.xs, mesh.ys, mesh.rows, mesh.cols, w, h)
        val corners = cornerQuad(mesh, w, h)
        val ratio = AspectRatioEstimator.estimate(corners, w, h)
        return OutputSize.forPage(width.longest, height.longest, ratio, maxLongEdge)
    }

    /** The mesh's four corners in pixels of a [width] × [height] image: top-left first, clockwise. */
    fun cornerQuad(mesh: PageMesh, width: Double, height: Double): Quad {
        val last = mesh.rows * mesh.cols - 1
        fun at(i: Int) = Point2(mesh.xs[i] * width, mesh.ys[i] * height)
        return Quad(at(0), at(mesh.cols - 1), at(last), at(last - (mesh.cols - 1)))
    }

    /**
     * The mesh's points as fractions of the stored original rather than of
     * the upright original: [sourceRotation] clockwise degrees make the stored
     * original upright (see [RecipeGeometry.uprightToRaw]). Returns (xs, ys).
     */
    fun toRaw(mesh: PageMesh, sourceRotation: Int): Pair<FloatArray, FloatArray> {
        val xs = mesh.xs.copyOf()
        val ys = mesh.ys.copyOf()
        val degrees = RecipeGeometry.normalizeDegrees(sourceRotation)
        if (degrees == 0) return xs to ys
        for (i in xs.indices) {
            val raw = RecipeGeometry.uprightToRaw(Point2(xs[i].toDouble(), ys[i].toDouble()), degrees)
            xs[i] = raw.x.toFloat()
            ys[i] = raw.y.toFloat()
        }
        return xs to ys
    }

    /**
     * Fills [mapX] and [mapY] (each [outWidth] × [outHeight], row by row) with
     * the pixel of a [sourceWidth] × [sourceHeight] image that each pixel of
     * the flattened page shows, for a mesh given by [xs] / [ys] (fractions of
     * that image, [rows] × [cols] points).
     */
    fun fillMaps(
        xs: FloatArray,
        ys: FloatArray,
        rows: Int,
        cols: Int,
        outWidth: Int,
        outHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        mapX: FloatArray,
        mapY: FloatArray,
    ) {
        require(mapX.size >= outWidth * outHeight && mapY.size >= outWidth * outHeight) { "maps too small" }
        forEachMapRow(xs, ys, rows, cols, outWidth, outHeight, sourceWidth, sourceHeight) { y, rowMapX, rowMapY ->
            rowMapX.copyInto(mapX, y * outWidth)
            rowMapY.copyInto(mapY, y * outWidth)
        }
    }

    /**
     * As [fillMaps], one output row at a time: [row] gets the row's index and
     * its x and y maps (the same two arrays every time), so a large page never
     * needs both full maps in memory at once.
     */
    fun forEachMapRow(
        xs: FloatArray,
        ys: FloatArray,
        rows: Int,
        cols: Int,
        outWidth: Int,
        outHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        row: (y: Int, mapX: FloatArray, mapY: FloatArray) -> Unit,
    ) {
        require(xs.size == rows * cols && ys.size == rows * cols && rows >= 2 && cols >= 2) { "one x and one y per mesh point" }
        val outX = FloatArray(outWidth)
        val outY = FloatArray(outWidth)
        // Per output column: the mesh column to its left and how far towards the next one.
        val colIndex = IntArray(outWidth)
        val colWeight = FloatArray(outWidth)
        for (x in 0 until outWidth) {
            val t = if (outWidth == 1) 0f else x.toFloat() * (cols - 1) / (outWidth - 1)
            val c = t.toInt().coerceIn(0, cols - 2)
            colIndex[x] = c
            colWeight[x] = t - c
        }
        val rowX = FloatArray(cols)
        val rowY = FloatArray(cols)
        val sw = sourceWidth.toFloat()
        val sh = sourceHeight.toFloat()
        for (y in 0 until outHeight) {
            // The mesh interpolated down to this output row, once per row.
            val t = if (outHeight == 1) 0f else y.toFloat() * (rows - 1) / (outHeight - 1)
            val r = t.toInt().coerceIn(0, rows - 2)
            val fy = t - r
            val above = r * cols
            val below = above + cols
            for (c in 0 until cols) {
                rowX[c] = (xs[above + c] * (1 - fy) + xs[below + c] * fy) * sw
                rowY[c] = (ys[above + c] * (1 - fy) + ys[below + c] * fy) * sh
            }
            for (x in 0 until outWidth) {
                val c = colIndex[x]
                val fx = colWeight[x]
                outX[x] = rowX[c] * (1 - fx) + rowX[c + 1] * fx
                outY[x] = rowY[c] * (1 - fx) + rowY[c + 1] * fx
            }
            row(y, outX, outY)
        }
    }

    /**
     * How far the page is from flat: the root-mean-square distance between the
     * mesh and the best perspective view of a flat page (a homography of the
     * mesh's lattice, fitted by least squares and refined on the distances
     * themselves), as a share of the page's diagonal. A flat page photographed
     * at an angle scores near 0; curled pages score higher. Measured in the
     * pixels of a [width] × [height] image, so the page's own proportions count.
     *
     * Matches the research benchmark's score (OpenCV `findHomography`, method
     * 0, which refines the same way): see docs/ai-image-research.md.
     */
    fun bend(xs: FloatArray, ys: FloatArray, rows: Int, cols: Int, width: Double, height: Double): Double {
        val n = rows * cols
        require(xs.size == n && ys.size == n && rows >= 2 && cols >= 2) { "rows × cols mesh points" }
        // Normalised coordinates keep the least-squares system well conditioned.
        var meanX = 0.0
        var meanY = 0.0
        for (i in 0 until n) {
            meanX += xs[i] * width
            meanY += ys[i] * height
        }
        meanX /= n
        meanY /= n
        var spread = 0.0
        for (i in 0 until n) spread += hypot(xs[i] * width - meanX, ys[i] * height - meanY)
        spread /= n
        if (spread <= 0.0) return 0.0
        val scale = sqrt(2.0) / spread
        val u = DoubleArray(n)
        val v = DoubleArray(n)
        val px = DoubleArray(n)
        val py = DoubleArray(n)
        for (i in 0 until n) {
            // The lattice is centred on 0 with unit spread, the photo points the same way.
            u[i] = ((i % cols).toDouble() / (cols - 1) - 0.5) * 2
            v[i] = ((i / cols).toDouble() / (rows - 1) - 0.5) * 2
            px[i] = (xs[i] * width - meanX) * scale
            py[i] = (ys[i] * height - meanY) * scale
        }
        val h = linearHomography(u, v, px, py) ?: return Double.NaN
        refine(h, u, v, px, py)
        val rms = sqrt(squaredError(h, u, v, px, py) / n) / scale
        val (meshWidth, meshHeight) = edgeLengths(xs, ys, rows, cols, width, height)
        val diagonal = hypot(meshWidth.mean.roundToInt().toDouble(), meshHeight.mean.roundToInt().toDouble())
        return if (diagonal > 0.0) rms / diagonal else 0.0
    }

    /** [PageMesh] form of [bend], for a mesh over a [width] × [height] image. */
    fun bend(mesh: PageMesh, width: Double, height: Double): Double = bend(mesh.xs, mesh.ys, mesh.rows, mesh.cols, width, height)

    /** Lengths of two opposite edges of the page. */
    class EdgePair(val first: Double, val second: Double) {
        val longest: Double get() = max(first, second)
        val mean: Double get() = (first + second) / 2
    }

    /** The page's top and bottom edge lengths, then its left and right, along the mesh, in pixels. */
    fun edgeLengths(xs: FloatArray, ys: FloatArray, rows: Int, cols: Int, width: Double, height: Double): Pair<EdgePair, EdgePair> {
        fun polyline(indices: IntProgression): Double {
            var length = 0.0
            var previous = -1
            for (i in indices) {
                if (previous >= 0) length += hypot((xs[i] - xs[previous]) * width, (ys[i] - ys[previous]) * height)
                previous = i
            }
            return length
        }
        val last = rows * cols - 1
        val top = polyline(0 until cols)
        val bottom = polyline((last - cols + 1)..last)
        val left = polyline(0..(last - cols + 1) step cols)
        val right = polyline((cols - 1)..last step cols)
        return EdgePair(top, bottom) to EdgePair(left, right)
    }

    /** Least squares for h (h[8] = 1) of x = (h0 u + h1 v + h2) / w, y = (h3 u + h4 v + h5) / w, w = h6 u + h7 v + 1. */
    private fun linearHomography(u: DoubleArray, v: DoubleArray, px: DoubleArray, py: DoubleArray): DoubleArray? {
        val ata = Array(PARAMS) { DoubleArray(PARAMS) }
        val atb = DoubleArray(PARAMS)
        val row = DoubleArray(PARAMS)
        for (i in u.indices) {
            for (axis in 0..1) {
                row.fill(0.0)
                val target = if (axis == 0) px[i] else py[i]
                val offset = axis * 3
                row[offset] = u[i]
                row[offset + 1] = v[i]
                row[offset + 2] = 1.0
                row[6] = -u[i] * target
                row[7] = -v[i] * target
                for (a in 0 until PARAMS) {
                    if (row[a] == 0.0) continue
                    atb[a] += row[a] * target
                    for (b in 0 until PARAMS) ata[a][b] += row[a] * row[b]
                }
            }
        }
        return solve(ata, atb)
    }

    /** Levenberg–Marquardt on the point distances themselves, from the linear solution. */
    private fun refine(h: DoubleArray, u: DoubleArray, v: DoubleArray, px: DoubleArray, py: DoubleArray) {
        var lambda = 1e-3
        var error = squaredError(h, u, v, px, py)
        val jtj = Array(PARAMS) { DoubleArray(PARAMS) }
        val jtr = DoubleArray(PARAMS)
        val jx = DoubleArray(PARAMS)
        val jy = DoubleArray(PARAMS)
        repeat(REFINE_ITERATIONS) {
            for (a in 0 until PARAMS) {
                jtj[a].fill(0.0)
                jtr[a] = 0.0
            }
            for (i in u.indices) {
                val w = h[6] * u[i] + h[7] * v[i] + 1.0
                if (abs(w) < 1e-12) continue
                val x = (h[0] * u[i] + h[1] * v[i] + h[2]) / w
                val y = (h[3] * u[i] + h[4] * v[i] + h[5]) / w
                jx.fill(0.0)
                jy.fill(0.0)
                jx[0] = u[i] / w; jx[1] = v[i] / w; jx[2] = 1.0 / w
                jy[3] = u[i] / w; jy[4] = v[i] / w; jy[5] = 1.0 / w
                jx[6] = -x * u[i] / w; jx[7] = -x * v[i] / w
                jy[6] = -y * u[i] / w; jy[7] = -y * v[i] / w
                val rx = x - px[i]
                val ry = y - py[i]
                for (a in 0 until PARAMS) {
                    jtr[a] += jx[a] * rx + jy[a] * ry
                    for (b in 0 until PARAMS) jtj[a][b] += jx[a] * jx[b] + jy[a] * jy[b]
                }
            }
            var improved = false
            while (!improved && lambda < 1e8) {
                val damped = Array(PARAMS) { a -> DoubleArray(PARAMS) { b -> jtj[a][b] + if (a == b) lambda * (1 + jtj[a][a]) else 0.0 } }
                val step = solve(damped, DoubleArray(PARAMS) { -jtr[it] }) ?: break
                val candidate = DoubleArray(PARAMS) { h[it] + step[it] }
                val candidateError = squaredError(candidate, u, v, px, py)
                if (candidateError < error) {
                    candidate.copyInto(h)
                    val gain = error - candidateError
                    error = candidateError
                    lambda = max(lambda / 10, 1e-12)
                    improved = true
                    if (gain < 1e-15 * (1 + error)) return
                } else {
                    lambda *= 10
                }
            }
            if (!improved) return
        }
    }

    private fun squaredError(h: DoubleArray, u: DoubleArray, v: DoubleArray, px: DoubleArray, py: DoubleArray): Double {
        var sum = 0.0
        for (i in u.indices) {
            val w = h[6] * u[i] + h[7] * v[i] + 1.0
            val dx = (h[0] * u[i] + h[1] * v[i] + h[2]) / w - px[i]
            val dy = (h[3] * u[i] + h[4] * v[i] + h[5]) / w - py[i]
            sum += dx * dx + dy * dy
        }
        return sum
    }

    /** Gaussian elimination with partial pivoting; null for a singular system. Leaves its inputs changed. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (abs(a[pivot][col]) < 1e-14) return null
            if (pivot != col) {
                val row = a[pivot]; a[pivot] = a[col]; a[col] = row
                val value = b[pivot]; b[pivot] = b[col]; b[col] = value
            }
            for (r in col + 1 until n) {
                val factor = a[r][col] / a[col][col]
                if (factor == 0.0) continue
                for (c in col until n) a[r][c] -= factor * a[col][c]
                b[r] -= factor * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var sum = b[r]
            for (c in r + 1 until n) sum -= a[r][c] * x[c]
            x[r] = sum / a[r][r]
        }
        return x
    }

    private const val PARAMS = 8
    private const val REFINE_ITERATIONS = 20

    /** Shortest page edge accepted, as a share of the image's longer side ([isUsable]). */
    private const val MIN_EDGE_FRACTION = 0.02
}
