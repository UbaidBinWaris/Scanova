package app.scanova.core.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportEstimateTest {

    private val page = PixelSize(1800, 2400)

    private fun source(
        pages: Int = 3,
        stored: Boolean = true,
        pdfBytes: Long? = 1_000_000,
        text: DocumentText = DocumentText(emptyList(), ""),
    ) = ExportSource(
        documentId = "d",
        title = "Lease",
        pageCount = pages,
        pageSizes = if (stored) List(pages) { page } else emptyList(),
        pdfSizeBytes = pdfBytes,
        text = text,
    )

    @Test
    fun `a PDF export is one file per document with the stored size`() {
        val summary = ExportEstimate.summarize(ExportFormat.PDF, ImageQuality.HIGH, listOf(source(), source(pages = 2, pdfBytes = 500)))
        assertEquals(2, summary.fileCount)
        assertEquals(5, summary.pageCount)
        assertEquals(1_000_500, summary.estimatedBytes)
    }

    @Test
    fun `images are one file per page and medium quality is smaller`() {
        val sources = listOf(source(pages = 4))
        val high = ExportEstimate.summarize(ExportFormat.IMAGES, ImageQuality.HIGH, sources)
        val medium = ExportEstimate.summarize(ExportFormat.IMAGES, ImageQuality.MEDIUM, sources)

        assertEquals(4, high.fileCount)
        assertTrue(high.estimatedBytes > 0)
        assertTrue(medium.estimatedBytes < high.estimatedBytes)
    }

    @Test
    fun `documents known only from their PDF are estimated as A4 pages`() {
        val summary = ExportEstimate.summarize(ExportFormat.IMAGES, ImageQuality.HIGH, listOf(source(pages = 2, stored = false)))
        assertEquals(2, summary.fileCount)
        assertTrue(summary.estimatedBytes > 0)
    }

    @Test
    fun `a long document makes several long images`() {
        val short = ExportEstimate.summarize(ExportFormat.LONG_IMAGE, ImageQuality.HIGH, listOf(source(pages = 2)))
        val long = ExportEstimate.summarize(ExportFormat.LONG_IMAGE, ImageQuality.HIGH, listOf(source(pages = 20)))
        assertEquals(1, short.fileCount)
        assertTrue(long.fileCount > 1)
        assertEquals(
            LongImageLayout.plan(List(20) { page }, maxWidth = ImageQuality.HIGH.longImageWidth).size,
            long.fileCount,
        )
    }

    @Test
    fun `text counts only documents that have text`() {
        val withText = source(text = DocumentText(listOf("Hello", "World"), "Hello\n\nWorld"))
        val summary = ExportEstimate.summarize(ExportFormat.TEXT, ImageQuality.HIGH, listOf(withText, source()))
        assertEquals(1, summary.fileCount)
        assertTrue(summary.estimatedBytes >= "HelloWorld".length)
    }
}
