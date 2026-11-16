package app.scanova.feature.scan.imaging

import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HeatmapDecoderTest {

    private val size = 128

    /** Four heatmaps, each a Gaussian of [peak] height centred on a corner (in cells). */
    private fun maps(vararg corners: Triple<Double, Double, Float>, sigma: Double = 2.0): FloatArray {
        val out = FloatArray(corners.size * size * size)
        corners.forEachIndexed { channel, (cx, cy, peak) ->
            for (y in 0 until size) for (x in 0 until size) {
                val d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                out[channel * size * size + y * size + x] = (peak * exp(-d2 / (2 * sigma * sigma))).toFloat()
            }
        }
        return out
    }

    private fun assertNear(expected: Double, actual: Double) = assertEquals(expected, actual, 0.004)

    @Test
    fun `confident corners come out at their centres, as fractions of the image`() {
        val corners = HeatmapDecoder.decode(
            maps(Triple(20.0, 15.0, 0.95f), Triple(100.5, 18.0, 0.9f), Triple(104.0, 110.25, 0.92f), Triple(22.0, 112.0, 0.88f)),
            count = 4,
            size = size,
        )
        assertNotNull(corners)
        corners!!
        // Cell i covers [i, i + 1) of the map: its centre is (i + 0.5) / size.
        assertNear((20.0 + 0.5) / size, corners[0].x)
        assertNear((15.0 + 0.5) / size, corners[0].y)
        assertNear((100.5 + 0.5) / size, corners[1].x)
        assertNear((110.25 + 0.5) / size, corners[2].y)
    }

    @Test
    fun `a faint corner falls back to its peak instead of losing the page`() {
        val corners = HeatmapDecoder.decode(
            maps(Triple(20.0, 15.0, 0.95f), Triple(100.0, 18.0, 0.12f), Triple(104.0, 110.0, 0.9f), Triple(22.0, 112.0, 0.9f)),
            count = 4,
            size = size,
        )
        assertNotNull(corners)
        assertNear((100.0 + 0.5) / size, corners!![1].x)
        assertNear((18.0 + 0.5) / size, corners[1].y)
    }

    @Test
    fun `a corner the model never sees means no page`() {
        assertNull(
            HeatmapDecoder.decode(
                maps(Triple(20.0, 15.0, 0.95f), Triple(100.0, 18.0, 0.02f), Triple(104.0, 110.0, 0.9f), Triple(22.0, 112.0, 0.9f)),
                count = 4,
                size = size,
            ),
        )
    }

    @Test
    fun `of two confident regions the larger one is the corner`() {
        val heatmap = maps(Triple(30.0, 30.0, 0.9f), sigma = 4.0)
        val small = maps(Triple(90.0, 90.0, 0.95f), sigma = 1.0)
        for (i in heatmap.indices) heatmap[i] = maxOf(heatmap[i], small[i])
        val corner = HeatmapDecoder.decode(heatmap, count = 1, size = size)!!.single()
        assertNear((30.0 + 0.5) / size, corner.x)
        assertNear((30.0 + 0.5) / size, corner.y)
    }

    @Test
    fun `a corner on the map's border stays inside it, next to the edge`() {
        val corner = HeatmapDecoder.decode(maps(Triple(0.0, 127.0, 0.9f)), count = 1, size = size)!!.single()
        assertEquals(true, corner.x in 0.0..1.0 && corner.y in 0.0..1.0)
        // Half of the blob lies outside the map, so its centre moves inward by under
        // a cell and a half (about 1 % of the picture), as with DocAligner's own decoding.
        assertEquals(true, corner.x < 1.5 / size && corner.y > (size - 1.5) / size)
    }
}
