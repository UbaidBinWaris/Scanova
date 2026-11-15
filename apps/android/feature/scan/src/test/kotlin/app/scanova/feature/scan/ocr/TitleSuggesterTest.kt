package app.scanova.feature.scan.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleSuggesterTest {

    @Test
    fun `the first heading-like line becomes the title`() {
        val text = """
            RENTAL AGREEMENT
            This agreement is made on 12 November 2026 between…
        """.trimIndent()

        assertEquals("RENTAL AGREEMENT", TitleSuggester.suggest(text))
    }

    @Test
    fun `dates, amounts and reference numbers are skipped`() {
        val text = """
            12/11/2026
            +92 300 1234567
            Ref: 000-4471-88
            Electricity bill
            Total 4,310.00
        """.trimIndent()

        assertEquals("Electricity bill", TitleSuggester.suggest(text))
    }

    @Test
    fun `whitespace and decoration around the heading are removed`() {
        assertEquals("Invoice No 42", TitleSuggester.suggest("  ***  Invoice   No 42  ***  \nbody"))
        assertEquals("Meeting notes", TitleSuggester.suggest("— Meeting notes —"))
    }

    @Test
    fun `long headings are cut at a word boundary`() {
        val title = TitleSuggester.suggest(
            "Agreement for the provision of residential broadband services and related equipment rental",
        )!!

        assertTrue(title.length <= 60)
        assertEquals("Agreement for the provision of residential broadband", title)
    }

    @Test
    fun `a single very long word is still cut`() {
        assertEquals(60, TitleSuggester.suggest("A".repeat(200))!!.length)
    }

    @Test
    fun `other scripts work`() {
        assertEquals("کرایہ نامہ", TitleSuggester.suggest("کرایہ نامہ\n12/11/2026"))
    }

    @Test
    fun `nothing usable gives no suggestion`() {
        assertNull(TitleSuggester.suggest(""))
        assertNull(TitleSuggester.suggest("   \n\n  "))
        assertNull(TitleSuggester.suggest("12/11/2026\n4,310.00\n--"))
        assertNull(TitleSuggester.suggest("ab"))
    }

    @Test
    fun `only the top of the page is considered`() {
        val text = (1..20).joinToString("\n") { "$it.00" } + "\nA heading far down the page"

        assertNull(TitleSuggester.suggest(text))
    }
}
