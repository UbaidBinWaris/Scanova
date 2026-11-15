package app.scanova.core.data.export

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportNamesTest {

    @Test
    fun `titles lose characters file systems reject`() {
        assertEquals("Lease 2026 (signed)", ExportNames.baseName("Lease/2026: (signed)?"))
        assertEquals("Invoice", ExportNames.baseName("  ..Invoice..  "))
        assertEquals("قرارداد اجاره", ExportNames.baseName("قرارداد\nاجاره"))
    }

    @Test
    fun `an empty title falls back to a generic name`() {
        assertEquals("Scanova document", ExportNames.baseName("///"))
        assertEquals("Scanova document.pdf", ExportNames.pdf(""))
    }

    @Test
    fun `long titles are shortened`() {
        assertEquals(80, ExportNames.baseName("x".repeat(300)).length)
    }

    @Test
    fun `each format gets its extension`() {
        assertEquals("Lease.pdf", ExportNames.pdf("Lease"))
        assertEquals("Lease.txt", ExportNames.text("Lease"))
        assertEquals("Lease.jpg", ExportNames.longImage("Lease", part = 1, parts = 1))
        assertEquals("Lease - part 2.jpg", ExportNames.longImage("Lease", part = 2, parts = 3))
    }

    @Test
    fun `page numbers are padded so files sort in page order`() {
        assertEquals("Lease - page 03.jpg", ExportNames.page("Lease", pageNumber = 3, pageCount = 7))
        assertEquals("Lease - page 003.jpg", ExportNames.page("Lease", pageNumber = 3, pageCount = 120))
        assertEquals("Lease - page 12.jpg", ExportNames.page("Lease", pageNumber = 12, pageCount = 12))
    }

    @Test
    fun `repeated names within one export get a counter before the extension`() {
        val names = ExportNames.Unique()
        assertEquals("Receipt.pdf", names.next("Receipt.pdf"))
        assertEquals("Receipt (2).pdf", names.next("Receipt.pdf"))
        assertEquals("Receipt (3).pdf", names.next("Receipt.pdf"))
        assertEquals("Other.pdf", names.next("Other.pdf"))
        // Case-insensitive, as most file systems are.
        assertEquals("OTHER (2).pdf", names.next("OTHER.pdf"))
        assertEquals("notes (2)", ExportNames.Unique().run { next("notes"); next("notes") })
    }
}
