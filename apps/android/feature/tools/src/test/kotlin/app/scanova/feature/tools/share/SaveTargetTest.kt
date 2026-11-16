package app.scanova.feature.tools.share

import app.scanova.core.data.export.ExportFormat
import app.scanova.core.data.export.ExportNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaveTargetTest {

    @Test
    fun `every export format has a save dialog of its own type`() {
        assertEquals(SaveFileKind.PDF, SaveFileKind.of(ExportFormat.PDF.mimeType))
        assertEquals(SaveFileKind.JPEG, SaveFileKind.of(ExportFormat.IMAGES.mimeType))
        assertEquals(SaveFileKind.JPEG, SaveFileKind.of(ExportFormat.LONG_IMAGE.mimeType))
        assertEquals(SaveFileKind.TEXT, SaveFileKind.of(ExportFormat.TEXT.mimeType))
        assertEquals(SaveFileKind.PDF, SaveFileKind.of("Application/PDF"))
        assertNull(SaveFileKind.of("application/zip"))
        ExportFormat.entries.forEach { format ->
            assertEquals("format $format", format.mimeType, SaveFileKind.of(format.mimeType)?.mimeType)
        }
    }

    @Test
    fun `one file gets the save-as dialog with the export's own name`() {
        val title = "Lease 2026"
        assertEquals(
            SaveTarget.OneFile(SaveFileKind.PDF, "Lease 2026.pdf"),
            SaveTarget.of(listOf(ExportNames.pdf(title)), ExportFormat.PDF.mimeType, listOf(title)),
        )
        assertEquals(
            SaveTarget.OneFile(SaveFileKind.JPEG, "Lease 2026.jpg"),
            SaveTarget.of(listOf(ExportNames.longImage(title, 1, 1)), ExportFormat.LONG_IMAGE.mimeType, listOf(title)),
        )
        assertEquals(
            SaveTarget.OneFile(SaveFileKind.JPEG, "Lease 2026 - page 01.jpg"),
            SaveTarget.of(listOf(ExportNames.page(title, 1, 1)), ExportFormat.IMAGES.mimeType, listOf(title)),
        )
        assertEquals(
            SaveTarget.OneFile(SaveFileKind.TEXT, "Lease 2026.txt"),
            SaveTarget.of(listOf(ExportNames.text(title)), ExportFormat.TEXT.mimeType, listOf(title)),
        )
    }

    @Test
    fun `the pages of one document go into a folder named after it`() {
        val title = "Tax: return/2026"
        val pages = (1..3).map { ExportNames.page(title, it, 3) }
        assertEquals(
            SaveTarget.Folder(subfolder = ExportNames.baseName(title)),
            SaveTarget.of(pages, ExportFormat.IMAGES.mimeType, listOf(title)),
        )
        assertEquals("Tax return 2026", ExportNames.baseName(title))
    }

    @Test
    fun `files of several documents go straight into the chosen folder`() {
        assertEquals(
            SaveTarget.Folder(subfolder = null),
            SaveTarget.of(listOf("A.pdf", "B.pdf"), ExportFormat.PDF.mimeType, listOf("A", "B")),
        )
    }

    @Test
    fun `nothing to save has no target`() {
        assertNull(SaveTarget.of(emptyList(), ExportFormat.PDF.mimeType, listOf("A")))
    }

    @Test
    fun `a file of an unknown type still gets saved, into a folder`() {
        assertEquals(SaveTarget.Folder(subfolder = null), SaveTarget.of(listOf("a.bin"), "application/octet-stream", listOf("A")))
    }
}
