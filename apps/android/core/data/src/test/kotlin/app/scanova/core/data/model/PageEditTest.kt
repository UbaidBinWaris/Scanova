package app.scanova.core.data.model

import org.junit.Assert.assertEquals
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
}
