package app.scanova.core.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageEditTest {

    private val full = PageEdit(
        sourceRotation = 90,
        crop = listOf(PagePoint(0.1f, 0.1f), PagePoint(0.9f, 0.12f), PagePoint(0.88f, 0.9f), PagePoint(0.12f, 0.92f)),
        rotation = 180,
        filter = "magic_color",
        filterStrength = 0.6f,
        brightness = 0.1f,
        contrast = -0.2f,
        markup = listOf(
            MarkupStroke(MarkupTool.HIGHLIGHTER, 0x80FFEB3BL, 0.02f, listOf(PagePoint(0.2f, 0.3f), PagePoint(0.6f, 0.3f))),
        ),
    )

    @Test
    fun `a recipe survives a round trip unchanged`() {
        assertEquals(full, PageEdit.decodeOrNull(PageEdit.encode(full)))
    }

    @Test
    fun `the stored form names its version and uses stable tool names`() {
        val json = PageEdit.encode(full)
        assertTrue(json, json.contains("\"v\":1"))
        assertTrue(json, json.contains("\"highlighter\""))
    }

    @Test
    fun `fields added by a newer app are ignored, missing ones take defaults`() {
        val edit = PageEdit.decodeOrNull("""{"v":1,"filter":"greyscale","sharpen":0.4}""")
        assertEquals(PageEdit(filter = PageFilters.GREYSCALE), edit)
    }

    @Test
    fun `a missing or damaged recipe reads as unknown`() {
        assertNull(PageEdit.decodeOrNull(null))
        assertNull(PageEdit.decodeOrNull(""))
        assertNull(PageEdit.decodeOrNull("{not json"))
    }

    private fun mesh(rows: Int = 45, cols: Int = 31): PageMesh {
        val xs = FloatArray(rows * cols) { i -> 0.1f + 0.8f * (i % cols) / (cols - 1) }
        val ys = FloatArray(rows * cols) { i -> 0.05f + 0.9f * (i / cols) / (rows - 1) }
        return PageMesh.of(rows, cols, xs, ys)
    }

    @Test
    fun `a flattening mesh survives a round trip, to a sixty-five-thousandth`() {
        val edit = full.copy(flatten = mesh())
        val read = PageEdit.decodeOrNull(PageEdit.encode(edit))!!
        assertEquals(edit, read)
        val mesh = read.flatten!!
        assertTrue(mesh.isValid)
        assertEquals(45 * 31, mesh.xs.size)
        assertEquals(0.1f, mesh.xs[0], 1f / 65535)
        assertEquals(0.9f, mesh.xs[30], 1f / 65535)
        assertEquals(0.95f, mesh.ys[45 * 31 - 1], 1f / 65535)
    }

    @Test
    fun `a 45 by 31 mesh adds about 7 KB to the stored recipe`() {
        val plain = PageEdit.encode(full).length
        val flattened = PageEdit.encode(full.copy(flatten = mesh())).length
        assertTrue("${flattened - plain} characters", flattened - plain in 7_000..8_000)
    }

    @Test
    fun `points outside the photo are kept on its edge`() {
        val mesh = PageMesh.of(2, 2, floatArrayOf(-0.2f, 1.3f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 2f))
        assertEquals(listOf(0f, 1f, 0f, 1f), mesh.xs.toList())
        assertEquals(1f, mesh.ys[3])
    }

    @Test
    fun `a damaged or oversized mesh is not valid and costs nothing`() {
        assertFalse(PageMesh(45, 31, "not base64 at all!").isValid)
        assertFalse(PageMesh(45, 31, mesh(2, 2).points).isValid) // wrong point count
        assertFalse(PageMesh(1, 31, mesh().points).isValid)
        val huge = PageMesh(100_000, 100_000, "AAAA")
        assertFalse(huge.isValid)
        assertEquals(0, huge.xs.size)
        // 65536 × 65536 overflows an Int to 0 points: still refused, not an empty "valid" mesh.
        assertFalse(PageMesh(65_536, 65_536, "").isValid)
    }

    @Test
    fun `recipes from before flattening read as not flattened`() {
        assertNull(PageEdit.decodeOrNull("""{"v":1,"filter":"enhanced"}""")!!.flatten)
    }
}
