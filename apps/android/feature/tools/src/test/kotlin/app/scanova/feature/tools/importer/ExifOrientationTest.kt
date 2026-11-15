package app.scanova.feature.tools.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExifOrientationTest {

    @Test
    fun `plain rotations become the recipe's source rotation`() {
        assertEquals(ExifTransform(0, false), ExifOrientation.transform(ExifOrientation.NORMAL))
        assertEquals(ExifTransform(90, false), ExifOrientation.transform(ExifOrientation.ROTATE_90))
        assertEquals(ExifTransform(180, false), ExifOrientation.transform(ExifOrientation.ROTATE_180))
        assertEquals(ExifTransform(270, false), ExifOrientation.transform(ExifOrientation.ROTATE_270))
    }

    @Test
    fun `mirrored orientations follow the AndroidX convention`() {
        assertEquals(ExifTransform(0, true), ExifOrientation.transform(ExifOrientation.FLIP_HORIZONTAL))
        assertEquals(ExifTransform(180, true), ExifOrientation.transform(ExifOrientation.FLIP_VERTICAL))
        assertEquals(ExifTransform(270, true), ExifOrientation.transform(ExifOrientation.TRANSPOSE))
        assertEquals(ExifTransform(90, true), ExifOrientation.transform(ExifOrientation.TRANSVERSE))
    }

    @Test
    fun `missing or unknown values leave the picture as stored`() {
        assertEquals(ExifTransform.NONE, ExifOrientation.transform(0))
        assertEquals(ExifTransform.NONE, ExifOrientation.transform(9))
        assertEquals(ExifTransform.NONE, ExifOrientation.transform(-1))
        assertTrue(ExifTransform.NONE.isIdentity)
        assertFalse(ExifOrientation.transform(ExifOrientation.ROTATE_90).isIdentity)
    }

    /**
     * Applies a transform to a pixel coordinate of a [width] × [height] image
     * (mirror first, then turn clockwise) and checks that each EXIF value maps
     * the stored image's top-left corner where the EXIF specification says the
     * picture's visual top-left is stored.
     */
    @Test
    fun `transforms put the stored corner where EXIF says it belongs`() {
        val width = 4
        val height = 3
        // Where the visual top-left (0, 0) ends up after the transform, per EXIF value:
        // the corner that holds the picture's top-left in storage must land on (0, 0).
        val storedTopLeft = mapOf(
            1 to (0 to 0),
            2 to (width - 1 to 0),
            3 to (width - 1 to height - 1),
            4 to (0 to height - 1),
            5 to (0 to 0),
            6 to (0 to height - 1),
            7 to (width - 1 to height - 1),
            8 to (width - 1 to 0),
        )
        for ((orientation, corner) in storedTopLeft) {
            val transform = ExifOrientation.transform(orientation)
            assertEquals("orientation $orientation", 0 to 0, apply(transform, corner, width, height))
        }
    }

    private fun apply(transform: ExifTransform, point: Pair<Int, Int>, width: Int, height: Int): Pair<Int, Int> {
        var (x, y) = point
        var w = width
        var h = height
        if (transform.mirrored) x = w - 1 - x
        repeat(transform.rotation / 90) {
            // One clockwise quarter turn: (x, y) → (h - 1 - y, x); the image becomes h × w.
            val turnedX = h - 1 - y
            y = x
            x = turnedX
            val previousWidth = w
            w = h
            h = previousWidth
        }
        return x to y
    }
}
