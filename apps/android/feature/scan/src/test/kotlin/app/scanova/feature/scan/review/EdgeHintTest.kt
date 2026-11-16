package app.scanova.feature.scan.review

import app.scanova.feature.scan.geometry.Quad
import app.scanova.feature.scan.session.CameraMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeHintTest {

    private val found = Quad.inset(1.0, 1.0, 0.1)
    private val moved = Quad.inset(1.0, 1.0, 0.2)

    @Test
    fun `a photo without page edges says so while its outline is untouched`() {
        // An imported photo keeps the whole picture (no outline).
        assertTrue(EdgeHint.shows(CameraMode.DOCUMENT, detectedOutline = null, outline = null, firstOutline = null, cropOpened = false))
        // A camera photo falls back to most of the picture.
        assertTrue(EdgeHint.shows(CameraMode.DOCUMENT, detectedOutline = null, outline = found, firstOutline = found, cropOpened = false))
    }

    @Test
    fun `found edges need no note`() {
        assertFalse(EdgeHint.shows(CameraMode.DOCUMENT, detectedOutline = found, outline = found, firstOutline = found, cropOpened = false))
    }

    @Test
    fun `the note goes once the user has dealt with the outline`() {
        assertFalse(EdgeHint.shows(CameraMode.DOCUMENT, detectedOutline = null, outline = moved, firstOutline = null, cropOpened = false))
        assertFalse(EdgeHint.shows(CameraMode.DOCUMENT, detectedOutline = null, outline = null, firstOutline = null, cropOpened = true))
    }

    @Test
    fun `an ID card page is composed, not cropped`() {
        assertFalse(EdgeHint.shows(CameraMode.ID_CARD, detectedOutline = null, outline = null, firstOutline = null, cropOpened = false))
    }
}
