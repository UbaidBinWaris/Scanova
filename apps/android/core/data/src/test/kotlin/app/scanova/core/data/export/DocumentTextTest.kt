package app.scanova.core.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTextTest {

    private val heading: (Int) -> String = { "Page $it" }

    @Test
    fun `pages with text become numbered sections, blank pages are skipped`() {
        val text = DocumentText(pages = listOf(" Dear tenant ", "", "Signed"), whole = "ignored")

        assertEquals(
            listOf(TextSection(1, "Dear tenant"), TextSection(3, "Signed")),
            text.sections(),
        )
        assertEquals("Page 1\n\nDear tenant\n\n\nPage 3\n\nSigned\n", text.toPlainText(heading))
    }

    @Test
    fun `a one-page document has no page heading`() {
        val text = DocumentText(pages = listOf("Receipt 42"), whole = "Receipt 42")
        assertEquals(listOf(TextSection(null, "Receipt 42")), text.sections())
        assertEquals("Receipt 42\n", text.toPlainText(heading))
    }

    @Test
    fun `without page texts the whole-document text is used`() {
        val text = DocumentText(pages = emptyList(), whole = "  Restored from backup  ")
        assertEquals(listOf(TextSection(null, "Restored from backup")), text.sections())
        assertFalse(text.isEmpty)
    }

    @Test
    fun `no text anywhere is empty`() {
        val text = DocumentText(pages = listOf("", "  "), whole = " ")
        assertTrue(text.isEmpty)
        assertEquals("", text.toPlainText(heading))
    }
}
