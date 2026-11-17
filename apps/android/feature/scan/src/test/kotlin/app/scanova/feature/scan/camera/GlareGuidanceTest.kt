package app.scanova.feature.scan.camera

import app.scanova.core.data.settings.FlashMode
import app.scanova.feature.scan.geometry.Quad
import org.junit.Assert.assertEquals
import org.junit.Test

class GlareGuidanceTest {

    private val page = Quad.inset(1.0, 1.0, 0.1)

    @Test
    fun `glare is told before anything but the picture being taken`() {
        assertEquals(Guidance.GLARE, CameraUiState(outline = page, glare = true).guidance())
        // Also without a page outline: glare can hide the page's edges.
        assertEquals(Guidance.GLARE, CameraUiState(glare = true).guidance())
        assertEquals(Guidance.CAPTURING, CameraUiState(outline = page, glare = true, isCapturing = true).guidance())
    }

    @Test
    fun `with the flash or torch on, the glare hint says to turn it off`() {
        val glaring = CameraUiState(outline = page, glare = true, flashAvailable = true)
        assertEquals(Guidance.GLARE_FLASH, glaring.copy(flash = FlashMode.TORCH).guidance())
        assertEquals(Guidance.GLARE_FLASH, glaring.copy(flash = FlashMode.ON).guidance())
        assertEquals(Guidance.GLARE_FLASH, glaring.copy(flash = FlashMode.AUTO).guidance())
        assertEquals(Guidance.GLARE, glaring.copy(flash = FlashMode.OFF).guidance())
        // A phone without a flash is never told to turn it off.
        assertEquals(Guidance.GLARE, glaring.copy(flashAvailable = false, flash = FlashMode.ON).guidance())
    }

    @Test
    fun `after a photo with flash glare, the hint asks to turn the flash off while it is on`() {
        val after = CameraUiState(outline = page, lastPhotoFlashGlare = true, flashAvailable = true)
        assertEquals(Guidance.FLASH_GLARE_IN_PHOTO, after.copy(flash = FlashMode.ON).guidance())
        assertEquals(Guidance.FLASH_GLARE_IN_PHOTO, after.copy(flash = FlashMode.AUTO).guidance())
        assertEquals(Guidance.READY, after.copy(flash = FlashMode.OFF).guidance())
        // The torch's glare shows in the preview, where the live check sees it.
        assertEquals(Guidance.READY, after.copy(flash = FlashMode.TORCH).guidance())
    }
}
