package app.scanova.core.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PdfRenderSizingTest {

    @Test
    fun `an A4 page at 200 dpi`() {
        // 595 × 842 pt = 8.26 × 11.69 in.
        assertEquals(PixelSize(1653, 2339), PdfRenderSizing.pixelSize(595, 842, dpi = 200, maxLongEdge = 3000))
    }

    @Test
    fun `72 dpi maps one point to one pixel`() {
        assertEquals(PixelSize(612, 792), PdfRenderSizing.pixelSize(612, 792, dpi = 72, maxLongEdge = 3000))
    }

    @Test
    fun `large pages are capped at the long edge and keep their proportions`() {
        // A2 landscape: 1684 × 1191 pt would be 4678 × 3308 px at 200 dpi.
        val size = PdfRenderSizing.pixelSize(1684, 1191, dpi = 200, maxLongEdge = 3000)
        assertEquals(3000, size.width)
        assertEquals(2122, size.height)
    }

    @Test
    fun `degenerate pages are treated as one point square`() {
        assertEquals(PixelSize(3, 3), PdfRenderSizing.pixelSize(1, 0, dpi = 200, maxLongEdge = 3000))
        assertEquals(PixelSize(1, 1), PdfRenderSizing.pixelSize(-5, 0, dpi = 10, maxLongEdge = 3000))
    }

    @Test
    fun `limits must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { PdfRenderSizing.pixelSize(595, 842, dpi = 0, maxLongEdge = 3000) }
    }

    @Test
    fun `fit only ever shrinks`() {
        assertEquals(PixelSize(1200, 1600), PdfRenderSizing.fit(PixelSize(1800, 2400), 1600))
        assertEquals(PixelSize(800, 600), PdfRenderSizing.fit(PixelSize(800, 600), 1600))
    }
}
