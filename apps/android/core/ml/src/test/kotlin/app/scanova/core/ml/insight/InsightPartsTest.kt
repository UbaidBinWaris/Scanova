package app.scanova.core.ml.insight

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchTextTest {
    @Test
    fun `OCR mix-ups inside words are folded on both sides`() {
        val text = MatchText.of("lNV0ICE No. 42\nT0TAL Payrnent")
        assertTrue(text.contains(MatchText.fold("invoice")))
        assertTrue(text.contains(MatchText.fold("total payment")))
    }

    @Test
    fun `digits and units are split and whole words only match`() {
        val text = MatchText.of("Tab Panadol 500mg, Billing month")
        assertTrue(text.contains(MatchText.fold("mg")))
        assertFalse(text.contains(MatchText.fold("bill")))
        assertEquals(2, MatchText.of("bill to: x. BILL TO y").count(MatchText.fold("bill to")))
    }
}

class DateFinderTest {
    private fun day(text: String): LocalDate? = DateFinder.pick(text)?.date

    @Test
    fun `common written forms`() {
        assertEquals(LocalDate.of(2026, 11, 16), day("Date: 16/11/2026"))
        assertEquals(LocalDate.of(2026, 11, 16), day("16-11-26"))
        assertEquals(LocalDate.of(2026, 11, 16), day("2026-11-16"))
        assertEquals(LocalDate.of(2026, 11, 16), day("16th November, 2026"))
        assertEquals(LocalDate.of(2026, 11, 16), day("Nov 16, 2026 4:32 PM"))
        assertEquals(LocalDate.of(2026, 11, 20), day("DATE 20NOV26"))
    }

    @Test
    fun `day first unless the numbers say otherwise`() {
        assertEquals(LocalDate.of(2026, 5, 3), day("03/05/2026"))
        assertEquals(LocalDate.of(2026, 5, 13), day("05/13/2026"))
    }

    @Test
    fun `OCR mix-ups next to digits are corrected`() {
        assertEquals(LocalDate.of(2026, 11, 1), day("Date 0l/11/2026"))
        assertEquals(LocalDate.of(2026, 10, 10), day("1O/1O/2026"))
    }

    @Test
    fun `due, expiry and birth dates are never the document date`() {
        val text = "Due Date: 20/11/2026\nDate of Birth: 14.08.1995\nIssue Date: 05/11/2026"
        assertEquals(LocalDate.of(2026, 11, 5), day(text))
        assertNull(DateFinder.pick("Expiry Date: 10.01.2031"))
    }

    @Test
    fun `a billing month is found as a month`() {
        val picked = DateFinder.pick("Billing Month: OCT 2026\nIssue Date: 05/11/2026", listOf(DateRole.PERIOD))!!
        assertEquals(YearMonth.of(2026, 10), picked.month)
        assertEquals("Oct 2026", picked.format(monthOnly = true))
    }

    @Test
    fun `phone numbers, amounts and references are not dates`() {
        assertNull(DateFinder.pick("Tel: 0300-1234567\nRef: 000-4471-88\nTotal 4,310.00\nMart 24"))
    }
}

class AutomaticTitlesTest {
    @Test
    fun `titles the app gives are automatic`() {
        assertTrue(AutomaticTitles.isAutomatic("Scan"))
        assertTrue(AutomaticTitles.isAutomatic("Scan 16 Nov 2026, 10:05"))
        assertTrue(AutomaticTitles.isAutomatic("Scan Nov 16, 2026, 2:05 PM"))
        assertTrue(AutomaticTitles.isAutomatic("Scan 16/11/2026 10:05"))
        assertTrue(AutomaticTitles.isAutomatic("Imported 16 Nov 2026"))
        assertTrue(AutomaticTitles.isAutomatic("Merged 16 Nov 2026"))
        assertTrue(AutomaticTitles.isAutomatic("Scan 16 Nov 2026, 10:05 (pages 1–3)"))
        assertTrue(AutomaticTitles.isAutomatic("Imported 16 Nov 2026 (page 2)"))
        assertTrue(AutomaticTitles.isAutomatic("Scan ١٦ نومبر ٢٠٢٦"))
    }

    @Test
    fun `titles a person typed are never automatic`() {
        assertFalse(AutomaticTitles.isAutomatic("Scan of passport"))
        assertFalse(AutomaticTitles.isAutomatic("Scanned receipts 2026"))
        assertFalse(AutomaticTitles.isAutomatic("Imported"))
        assertFalse(AutomaticTitles.isAutomatic("Imported photos from Ali"))
        assertFalse(AutomaticTitles.isAutomatic("Receipt – Imtiaz Store – 16 Nov 2026"))
        assertFalse(AutomaticTitles.isAutomatic("Electricity bill"))
        assertFalse(AutomaticTitles.isAutomatic(""))
    }

    @Test
    fun `a split part keeps its pages`() {
        assertEquals("Bill – PTCL – Oct 2026 (pages 1–3)", AutomaticTitles.retitle("Scan 16 Nov 2026 (pages 1–3)", "Bill – PTCL – Oct 2026"))
        assertEquals("Bill – PTCL – Oct 2026", AutomaticTitles.retitle("Imported 16 Nov 2026", "Bill – PTCL – Oct 2026"))
    }
}

class HeadingsTest {
    @Test
    fun `capitals become title case, acronyms stay`() {
        assertEquals("Imtiaz Super Market", Headings.tidy("IMTIAZ SUPER MARKET"))
        assertEquals("K-Electric", Headings.tidy("K-ELECTRIC"))
        assertEquals("Certificate of Achievement", Headings.tidy("CERTIFICATE OF ACHIEVEMENT"))
        assertEquals("PSO Service Station", Headings.tidy("PSO SERVICE STATION"))
        assertEquals("PTCL", Headings.tidy("PTCL"))
        assertEquals("Imtiaz Store", Headings.tidy("lMTlAZ STORE"))
        assertEquals("Gloria Jean's Coffees", Headings.tidy("Gloria Jean's Coffees"))
    }

    @Test
    fun `noise does not read as words`() {
        assertFalse(Headings.readsAsWords("Jl Lsl"))
        assertTrue(Headings.readsAsWords("Rental Agreement"))
        assertTrue(Headings.readsAsWords("کرایہ نامہ"))
    }
}

class DocumentTypeTest {
    @Test
    fun `tags name types regardless of case`() {
        assertEquals(DocumentType.RECEIPT, DocumentType.fromTag("receipt"))
        assertEquals(DocumentType.ID_CARD, DocumentType.fromTag(" ID Card "))
        assertEquals(DocumentType.BANK_STATEMENT, DocumentType.fromTags(listOf("tax 2026", "Bank statement")))
        assertNull(DocumentType.fromTag("other"))
        assertNull(DocumentType.fromTags(listOf("tax 2026")))
    }
}
