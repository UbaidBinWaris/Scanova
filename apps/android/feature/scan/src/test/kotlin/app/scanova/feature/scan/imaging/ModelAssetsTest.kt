package app.scanova.feature.scan.imaging

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled page model is exactly the reviewed file (assets/models/README.md):
 * a replaced or corrupted model fails the build, not a user's scan.
 */
class ModelAssetsTest {

    private fun asset(name: String): File {
        // Unit tests run with the module directory as the working directory.
        val file = File("src/main/assets/models/$name")
        assertTrue("missing ${file.path}", file.isFile)
        return file
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun `the page model is the reviewed DocAligner lcnet100 file`() {
        val file = asset(AiPageDetector.ASSET.substringAfterLast('/'))
        assertEquals("f4117b786e3a18470f3865c93f3c2bd69d9b998edd60f385574a5c665e79594e", sha256(file))
        assertEquals(4_767_987L, file.length())
    }

    @Test
    fun `its Apache 2 licence ships with it`() {
        assertTrue(asset("docaligner-LICENSE.txt").readText().contains("Apache License"))
    }

    @Test
    fun `the flattening model is the reviewed UVDoc mesh export`() {
        val file = asset(PageFlattener.ASSET.substringAfterLast('/'))
        assertEquals("d2cff00114e406445e85fc685f8b8ef4d255361109c7a5a14367b1f2b57316ce", sha256(file))
        assertEquals(16_041_896L, file.length())
    }

    @Test
    fun `UVDoc's MIT licence ships with it`() {
        val licence = asset("uvdoc-LICENSE.txt").readText()
        assertTrue(licence.contains("MIT License") && licence.contains("Tanguy MAGNE"))
    }
}
