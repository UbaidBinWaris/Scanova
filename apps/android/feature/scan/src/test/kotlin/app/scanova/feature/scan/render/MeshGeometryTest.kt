package app.scanova.feature.scan.render

import app.scanova.core.data.model.PageMesh
import app.scanova.feature.scan.geometry.Point2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshGeometryTest {

    private val rows = 45
    private val cols = 31

    /** A flat page seen in perspective: the lattice through a homography, as fractions of a 1000 × 1000 image. */
    private fun perspectiveMesh(): PageMesh {
        val xs = FloatArray(rows * cols)
        val ys = FloatArray(rows * cols)
        for (r in 0 until rows) for (c in 0 until cols) {
            val u = c.toDouble() / (cols - 1)
            val v = r.toDouble() / (rows - 1)
            val w = 1 + 0.25 * u + 0.1 * v
            xs[r * cols + c] = ((0.15 + 0.6 * u + 0.05 * v) / w).toFloat()
            ys[r * cols + c] = ((0.1 + 0.03 * u + 0.8 * v) / w).toFloat()
        }
        return PageMesh.of(rows, cols, xs, ys)
    }

    /** The same page with its lower half curling: rows bend up and down with the column. */
    private fun curledMesh(amplitude: Double): PageMesh {
        val flat = perspectiveMesh()
        val ys = flat.ys.copyOf()
        for (r in 0 until rows) for (c in 0 until cols) {
            val v = r.toDouble() / (rows - 1)
            ys[r * cols + c] += (amplitude * v * v * sin(PI * c / (cols - 1))).toFloat()
        }
        return PageMesh.of(rows, cols, flat.xs, ys)
    }

    @Test
    fun `a flat page in perspective is not bent`() {
        assertEquals(0.0, MeshGeometry.bend(perspectiveMesh(), 1000.0, 1000.0), 2e-5)
    }

    @Test
    fun `the more a page curls the higher it scores, past the flattening threshold`() {
        val slight = MeshGeometry.bend(curledMesh(0.005), 1000.0, 1000.0)
        val strong = MeshGeometry.bend(curledMesh(0.04), 1000.0, 1000.0)
        assertTrue("slight $slight", slight < PageFlattening.BENT)
        assertTrue("strong $strong", strong > PageFlattening.BENT)
        assertTrue(strong > slight)
    }

    private data class Fixture(val set: String, val width: Double, val height: Double, val bend: Double, val mesh: PageMesh)

    private fun fixtures(): List<Fixture> =
        javaClass.getResourceAsStream("/uvdoc-mesh-fixtures.txt")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val (set, width, height, bend, points) = line.split(" ")
                Fixture(set, width.toDouble(), height.toDouble(), bend.toDouble(), PageMesh(rows, cols, points))
            }

    @Test
    fun `scores real UVDoc meshes as the research benchmark did`() {
        val all = fixtures()
        assertEquals(3, all.size)
        for (fixture in all) {
            assertTrue(fixture.set, fixture.mesh.isValid)
            val score = MeshGeometry.bend(fixture.mesh, fixture.width, fixture.height)
            assertEquals("${fixture.set}: $score vs ${fixture.bend}", fixture.bend, score, fixture.bend * 0.02)
        }
        // Two curled book pages above the threshold, the flat SmartDoc page below it.
        assertTrue(all.filter { it.set == "book100" }.all { MeshGeometry.bend(it.mesh, it.width, it.height) > PageFlattening.BENT })
        assertTrue(all.filter { it.set.startsWith("smartdoc") }.all { MeshGeometry.bend(it.mesh, it.width, it.height) < PageFlattening.BENT })
    }

    @Test
    fun `the page's corners show the mesh's corners and the maps interpolate between`() {
        val mesh = perspectiveMesh()
        val width = 200
        val height = 300
        val mapX = FloatArray(width * height)
        val mapY = FloatArray(width * height)
        MeshGeometry.fillMaps(mesh.xs, mesh.ys, rows, cols, width, height, 1000, 800, mapX, mapY)
        fun near(expected: Float, actual: Float) = assertEquals(expected, actual, 1e-3f)
        near(mesh.xs[0] * 1000, mapX[0])
        near(mesh.ys[0] * 800, mapY[0])
        near(mesh.xs[cols - 1] * 1000, mapX[width - 1])
        near(mesh.ys[rows * cols - 1] * 800, mapY[width * height - 1])
        near(mesh.xs[(rows - 1) * cols] * 1000, mapX[(height - 1) * width])
        // Halfway down and across: the mesh's middle point (rows and cols are odd).
        val middle = (rows / 2) * cols + cols / 2
        val atMiddle = MeshGeometry.run {
            val maps = Pair(FloatArray(3 * 3), FloatArray(3 * 3))
            fillMaps(mesh.xs, mesh.ys, rows, cols, 3, 3, 1000, 800, maps.first, maps.second)
            Point2(maps.first[4].toDouble(), maps.second[4].toDouble())
        }
        near(mesh.xs[middle] * 1000, atMiddle.x.toFloat())
        near(mesh.ys[middle] * 800, atMiddle.y.toFloat())
    }

    @Test
    fun `a page's size follows its edges and its proportions`() {
        // A frontal rectangle covering 60 % × 70 % of a 1000 × 2000 photo: 600 × 1400 px.
        val xs = FloatArray(rows * cols) { i -> 0.2f + 0.6f * (i % cols) / (cols - 1) }
        val ys = FloatArray(rows * cols) { i -> 0.1f + 0.7f * (i / cols) / (rows - 1) }
        val size = MeshGeometry.outputSize(PageMesh.of(rows, cols, xs, ys), 1000, 2000, 2400)
        assertTrue("$size", abs(size.width - 600) <= 4 && abs(size.height - 1400) <= 4)
        val capped = MeshGeometry.outputSize(PageMesh.of(rows, cols, xs, ys), 1000, 2000, 700)
        assertEquals(700, capped.height)
    }

    @Test
    fun `a collapsed or twisted mesh is not a page, a real one is`() {
        assertTrue(MeshGeometry.isUsable(perspectiveMesh(), 1000.0, 1000.0))
        // Every point on one spot (what a damaged recipe can hold).
        val collapsed = PageMesh.of(rows, cols, FloatArray(rows * cols), FloatArray(rows * cols))
        assertFalse(MeshGeometry.isUsable(collapsed, 1000.0, 1000.0))
        // A sliver: 1 % of the photo wide.
        val sliver = PageMesh.of(rows, cols, FloatArray(rows * cols) { i -> 0.5f + 0.01f * (i % cols) / (cols - 1) }, perspectiveMesh().ys)
        assertFalse(MeshGeometry.isUsable(sliver, 1000.0, 1000.0))
        // Left and right swapped everywhere: a mirror image of the page.
        val mirrored = PageMesh.of(rows, cols, FloatArray(rows * cols) { i -> 0.9f - 0.8f * (i % cols) / (cols - 1) }, perspectiveMesh().ys)
        assertFalse(MeshGeometry.isUsable(mirrored, 1000.0, 1000.0))
        // Only the top row swapped: the outline crosses itself.
        val twisted = PageMesh.of(
            rows,
            cols,
            FloatArray(rows * cols) { i -> if (i < cols) 0.9f - 0.8f * i / (cols - 1) else 0.1f + 0.8f * (i % cols) / (cols - 1) },
            perspectiveMesh().ys,
        )
        assertFalse(MeshGeometry.isUsable(twisted, 1000.0, 1000.0))
    }

    @Test
    fun `a mesh is carried into the stored original's frame like the crop outline`() {
        val mesh = perspectiveMesh()
        for (rotation in listOf(0, 90, 180, 270)) {
            val (xs, ys) = MeshGeometry.toRaw(mesh, rotation)
            for (i in listOf(0, 17, mesh.xs.size - 1)) {
                val expected = RecipeGeometry.uprightToRaw(Point2(mesh.xs[i].toDouble(), mesh.ys[i].toDouble()), rotation)
                assertEquals(expected.x, xs[i].toDouble(), 1e-6)
                assertEquals(expected.y, ys[i].toDouble(), 1e-6)
            }
        }
    }
}
