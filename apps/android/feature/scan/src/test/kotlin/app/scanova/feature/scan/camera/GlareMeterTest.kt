package app.scanova.feature.scan.camera

import app.scanova.feature.scan.camera.SyntheticFrames.HEIGHT
import app.scanova.feature.scan.camera.SyntheticFrames.Spot
import app.scanova.feature.scan.camera.SyntheticFrames.WIDTH
import app.scanova.feature.scan.camera.SyntheticFrames.frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlareMeterTest {

    private val spot = 0.03f
    private val smallSpot = GlareSpotFinder.MIN_SPOT_SHARE * 0.75f

    @Test
    fun `one glaring frame does not show the hint, three of the last five do`() {
        val meter = GlareMeter()
        assertFalse(meter.onSpot(spot))
        assertFalse(meter.onSpot(0f))
        assertFalse(meter.onSpot(spot))
        assertTrue(meter.onSpot(spot))
    }

    @Test
    fun `glare seen in every other frame does not make the hint flicker`() {
        val meter = GlareMeter()
        val shown = (0 until 30).map { frame -> meter.onSpot(if (frame % 2 == 0) spot else 0f) }
        // It comes on once and then stays: never on-off-on.
        val changes = shown.zipWithNext().count { (before, after) -> before != after }
        assertEquals(1, changes)
        assertTrue(shown.last())
    }

    @Test
    fun `once shown, a smaller spot keeps it, and it goes after five clean frames`() {
        val meter = GlareMeter()
        repeat(3) { meter.onSpot(spot) }
        assertTrue(meter.showing)
        // Too small to bring the hint up, big enough to keep it: hysteresis on size as well as time.
        repeat(10) { assertTrue(meter.onSpot(smallSpot)) }
        repeat(4) { assertTrue(meter.onSpot(0f)) }
        assertFalse(meter.onSpot(0f))
    }

    @Test
    fun `a small spot alone never brings the hint up`() {
        val meter = GlareMeter()
        repeat(10) { assertFalse(meter.onSpot(smallSpot)) }
    }

    @Test
    fun `each frame says whether it shows glare itself, for auto-capture`() {
        val meter = GlareMeter()
        meter.onSpot(spot)
        assertTrue(meter.inFrame)
        assertFalse(meter.showing)
        meter.onSpot(0f)
        assertFalse(meter.inFrame)
        // A spot too small for the hint never holds auto-capture back unexplained.
        meter.onSpot(smallSpot)
        assertFalse(meter.inFrame)
    }

    @Test
    fun `camera frames with a glare spot on the page bring the hint up, sparkles do not`() {
        val glaring = frame(spots = listOf(Spot(320, 240, radius = 40.0)))
        val sparkling = frame(spots = (0 until 6).map { Spot(140 + it * 70, 240, radius = 3.0) })
        val meter = GlareMeter()
        repeat(5) { assertFalse(meter.onFrame(sparkling, WIDTH, HEIGHT, SyntheticFrames.PAGE)) }
        repeat(2) { meter.onFrame(glaring, WIDTH, HEIGHT, SyntheticFrames.PAGE) }
        assertTrue(meter.onFrame(glaring, WIDTH, HEIGHT, SyntheticFrames.PAGE))
    }
}
