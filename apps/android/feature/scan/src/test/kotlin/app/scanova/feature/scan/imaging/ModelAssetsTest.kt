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

    @Test
    fun `the page model is the reviewed DocAligner lcnet100 file`() {
        val file = asset(AiPageDetector.ASSET.substringAfterLast('/'))
        val sha256 = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("f4117b786e3a18470f3865c93f3c2bd69d9b998edd60f385574a5c665e79594e", sha256)
        assertEquals(4_767_987L, file.length())
    }

    @Test
    fun `its Apache 2 licence ships with it`() {
        assertTrue(asset("docaligner-LICENSE.txt").readText().contains("Apache License"))
    }
}
