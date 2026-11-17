package app.scanova.feature.scan.camera

import app.scanova.feature.scan.camera.SyntheticFrames.HEIGHT
import app.scanova.feature.scan.camera.SyntheticFrames.Spot
import app.scanova.feature.scan.camera.SyntheticFrames.WIDTH
import app.scanova.feature.scan.camera.SyntheticFrames.frame
import app.scanova.feature.scan.geometry.Point2
import app.scanova.feature.scan.geometry.Quad
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlareSpotFinderTest {

    private val finder = GlareSpotFinder()

    private fun spot(pixels: ByteArray, page: Quad? = SyntheticFrames.PAGE): Float =
        finder.largestSpot(pixels, WIDTH, HEIGHT, page)

    @Test
    fun `a printed page without glare has no spot`() {
        assertEquals(0f, spot(frame()), 0f)
    }

    @Test
    fun `a big blown-out spot on the page is glare, measured against the page`() {
        val share = spot(frame(spots = listOf(Spot(320, 240, radius = 40.0))))
        assertTrue(GlareSpotFinder.isGlare(share))
        // The blown-out disc (with the brightest part of its rim) is about 4 % of the page's inner part.
        assertTrue("share $share", share in 0.025f..0.05f)
    }

    @Test
    fun `tiny sparkles are not glare, even when together they cover more than a spot would`() {
        val sparkles = (0 until 8).flatMap { row -> (0 until 10).map { col -> Spot(110 + col * 45, 80 + row * 45, radius = 3.0) } }
        val pixels = frame(spots = sparkles)
        // Together they blow out more of the page than the smallest spot that counts…
        assertTrue(SyntheticFrames.share(pixels, 250) > GlareSpotFinder.MIN_SPOT_SHARE)
        // …but each one alone is a speck.
        assertFalse(GlareSpotFinder.isGlare(spot(pixels)))
    }

    @Test
    fun `a page that is bright all over is not glare`() {
        assertFalse(GlareSpotFinder.isGlare(spot(frame(paper = 252))))
        // Even with the brightest part of it right at the top of the range.
        assertFalse(GlareSpotFinder.isGlare(spot(frame(paper = 240, spots = listOf(Spot(320, 240, radius = 40.0))))))
    }

    @Test
    fun `a bright patch that is not blown out is not glare`() {
        // The print under it is still in the photo: the clean-up can bring it out.
        assertFalse(GlareSpotFinder.isGlare(spot(frame(paper = 190, spots = listOf(Spot(320, 240, radius = 40.0, level = 232))))))
    }

    @Test
    fun `glare beside the page does not count`() {
        // A shiny table next to the page.
        assertFalse(GlareSpotFinder.isGlare(spot(frame(spots = listOf(Spot(30, 240, radius = 25.0))))))
    }

    @Test
    fun `on a tilted page only what is inside the outline counts`() {
        val tilted = Quad(Point2(0.30, 0.10), Point2(0.85, 0.25), Point2(0.70, 0.90), Point2(0.15, 0.75))
        // Inside the page's bounding box, but on the table beyond its top-left edge.
        val besideTheEdge = frame(page = tilted, spots = listOf(Spot(110, 60, radius = 25.0)))
        assertFalse(GlareSpotFinder.isGlare(spot(besideTheEdge, tilted)))
        val onThePage = frame(page = tilted, spots = listOf(Spot(320, 240, radius = 35.0)))
        assertTrue(GlareSpotFinder.isGlare(spot(onThePage, tilted)))
    }

    @Test
    fun `without a page outline the middle of the frame is looked at`() {
        // A page filling the whole frame, its edges out of sight.
        val pixels = frame(page = null, spots = listOf(Spot(320, 240, radius = 30.0)))
        assertTrue(GlareSpotFinder.isGlare(spot(pixels, page = null)))
        assertFalse(GlareSpotFinder.isGlare(spot(frame(page = null), page = null)))
    }

    @Test
    fun `a dark scene is not a page`() {
        // A lamp in a dark room: bright, but there is no lit paper around it.
        assertFalse(GlareSpotFinder.isGlare(spot(frame(paper = 40, ink = 20, page = null, spots = listOf(Spot(320, 240, radius = 30.0))), page = null)))
    }

    @Test
    fun `a flattened photo of the page is checked as a whole`() {
        // The page alone, as the camera's quick preview shows it after a picture.
        val pixels = frame(page = null, spots = listOf(Spot(500, 120, radius = 45.0)))
        assertTrue(GlareSpotFinder.isGlare(finder.largestSpot(pixels, WIDTH, HEIGHT, GlareSpotFinder.WHOLE_PAGE)))
    }

    @Test
    fun `nothing usable gives no spot`() {
        assertEquals(0f, finder.largestSpot(ByteArray(0), 0, 0, null), 0f)
        assertEquals(0f, finder.largestSpot(ByteArray(10), WIDTH, HEIGHT, null), 0f)
    }

    @Test
    fun `brightness from colour follows the camera's Y plane`() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val grey = 0xFF808080.toInt()
        val green = 0xFF00FF00.toInt()
        val levels = GlareSpotFinder.luminanceOf(intArrayOf(white, black, grey, green)).map { it.toInt() and 0xFF }
        assertArrayEquals(intArrayOf(255, 0, 128, 149), levels.toIntArray())
    }
}
