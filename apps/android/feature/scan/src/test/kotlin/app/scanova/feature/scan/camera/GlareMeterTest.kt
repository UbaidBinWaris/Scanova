package app.scanova.feature.scan.camera

import app.scanova.feature.scan.geometry.Point2
import app.scanova.feature.scan.geometry.Quad
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlareMeterTest {

    private val width = 400
    private val height = 300

    /** The page fills the middle of the frame. */
    private val page = Quad(Point2(0.1, 0.1), Point2(0.9, 0.1), Point2(0.9, 0.9), Point2(0.1, 0.9))

    /** A frame: background at 60, paper at [paper], and a disc of [spot] of [radius] px at the centre. */
    private fun frame(paper: Int, spot: Int = paper, radius: Int = 0): ByteArray {
        val pixels = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val onPage = x in 40 until 360 && y in 30 until 270
                val dx = x - width / 2
                val dy = y - height / 2
                val level = when {
                    !onPage -> 60
                    dx * dx + dy * dy <= radius * radius -> spot
                    else -> paper
                }
                pixels[y * width + x] = level.toByte()
            }
        }
        return pixels
    }

    @Test
    fun `a blown-out spot on ordinary paper is a reflection`() {
        assertTrue(GlareMeter.hasReflection(frame(paper = 205, spot = 255, radius = 25), width, height, page))
    }

    @Test
    fun `a page that is bright all over is not a reflection`() {
        assertFalse(GlareMeter.hasReflection(frame(paper = 252), width, height, page))
    }

    @Test
    fun `a speck of glitter is not a reflection`() {
        assertFalse(GlareMeter.hasReflection(frame(paper = 205, spot = 255, radius = 2), width, height, page))
    }

    @Test
    fun `a bright spot below the sensor's limit is not blown out`() {
        assertFalse(GlareMeter.hasReflection(frame(paper = 205, spot = 240, radius = 25), width, height, page))
    }

    @Test
    fun `without a page nothing is reported, and one glaring frame does not make the hint flicker`() {
        val meter = GlareMeter()
        val glaring = frame(paper = 205, spot = 255, radius = 25)
        assertFalse(meter.onFrame(glaring, width, height, null))
        assertFalse(meter.onFrame(glaring, width, height, page))
        assertFalse(meter.onFrame(glaring, width, height, page))
        // Third glaring frame out of the last five: now it shows.
        assertTrue(meter.onFrame(glaring, width, height, page))
        // It stays a moment after the reflection has gone (no flicker), then clears.
        val clean = frame(paper = 205)
        assertTrue(meter.onFrame(clean, width, height, page))
        assertTrue(meter.onFrame(clean, width, height, page))
        assertFalse(meter.onFrame(clean, width, height, page))
    }
}
