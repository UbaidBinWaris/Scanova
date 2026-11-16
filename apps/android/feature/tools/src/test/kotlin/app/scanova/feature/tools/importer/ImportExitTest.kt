package app.scanova.feature.tools.importer

import app.scanova.feature.tools.common.CreatedDocument
import app.scanova.feature.tools.common.ToolError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportExitTest {

    private val lease = CreatedDocument("doc-1", "Lease", 3)
    private val invoice = CreatedDocument("doc-2", "Invoice", 1)
    private val pictures = listOf("content://picker/1", "content://picker/2")

    @Test
    fun `picked files are sorted into PDFs, pictures and the rest`() {
        assertEquals(ImportedFileKind.PDF, ImportedFileKind.of("application/pdf"))
        assertEquals(ImportedFileKind.PICTURE, ImportedFileKind.of("image/jpeg"))
        assertEquals(ImportedFileKind.PICTURE, ImportedFileKind.of("image/heic"))
        assertEquals(ImportedFileKind.PICTURE, ImportedFileKind.of("image/*"))
        assertEquals(ImportedFileKind.OTHER, ImportedFileKind.of("application/zip"))
        assertEquals(ImportedFileKind.OTHER, ImportedFileKind.of(null))
    }

    @Test
    fun `only pictures go straight to the scanner`() {
        assertEquals(ImportExit.ScanPictures(pictures), exitAfter(ImportStage.Done(emptyList(), emptyList(), pictures)))
    }

    @Test
    fun `one PDF opens straight away`() {
        assertEquals(ImportExit.Open("doc-1"), exitAfter(ImportStage.Done(listOf(lease), emptyList(), emptyList())))
    }

    @Test
    fun `the result is shown first when there is more to say`() {
        // Several documents.
        assertNull(exitAfter(ImportStage.Done(listOf(lease, invoice), emptyList(), emptyList())))
        // A document and pictures: the pictures wait for "Scan 2 pictures".
        assertNull(exitAfter(ImportStage.Done(listOf(lease), emptyList(), pictures)))
        // Something was skipped.
        assertNull(exitAfter(ImportStage.Done(listOf(lease), listOf(ToolError.Unreadable("a.zip")), emptyList())))
        assertNull(exitAfter(ImportStage.Done(emptyList(), listOf(ToolError.Unreadable("a.zip")), pictures)))
    }

    @Test
    fun `failures and work in progress never leave on their own`() {
        assertNull(exitAfter(ImportStage.Failed(ToolError.Unreadable(null))))
        assertNull(exitAfter(ImportStage.Picking))
    }
}
