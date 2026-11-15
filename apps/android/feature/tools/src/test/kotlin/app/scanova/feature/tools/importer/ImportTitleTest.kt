package app.scanova.feature.tools.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportTitleTest {

    @Test
    fun `an imported PDF is titled after its file name without the extension`() {
        assertEquals("Lease 2026", titleFromFileName("Lease 2026.pdf"))
        assertEquals("tax return.final", titleFromFileName("tax return.final.PDF"))
    }

    @Test
    fun `underscores read as spaces`() {
        assertEquals("scan 2026 11 16", titleFromFileName("scan_2026_11_16.pdf"))
    }

    @Test
    fun `names without an extension are kept`() {
        assertEquals("Contract", titleFromFileName("Contract"))
    }

    @Test
    fun `nothing usable gives no title`() {
        assertNull(titleFromFileName(".pdf"))
        assertNull(titleFromFileName("  _ .pdf"))
    }
}
