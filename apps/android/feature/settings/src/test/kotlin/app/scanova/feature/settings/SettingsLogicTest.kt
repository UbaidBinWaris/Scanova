package app.scanova.feature.settings

import app.scanova.core.data.model.PairedServer
import app.scanova.core.data.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLogicTest {

    private val user = User(
        id = "u1",
        email = "ayesha@example.com",
        name = "Ayesha",
        mustChangePassword = false,
        storageUsedBytes = 0,
        storageQuotaBytes = null,
    )

    private fun server(isDefault: Boolean) = PairedServer(
        url = "https://scanova.example.com",
        isDefault = isDefault,
        caFingerprint = null,
        name = null,
        serverVersion = null,
        serverOcr = false,
        ai = false,
    )

    @Test
    fun `storage share needs a positive quota and stays between 0 and 1`() {
        assertNull(storageFraction(usedBytes = 10, quotaBytes = null))
        assertNull(storageFraction(usedBytes = 10, quotaBytes = 0))
        assertEquals(0.5f, storageFraction(usedBytes = 1_000, quotaBytes = 2_000)!!, 0.0001f)
        assertEquals(1f, storageFraction(usedBytes = 3_000, quotaBytes = 2_000)!!, 0.0001f)
        assertEquals(0f, storageFraction(usedBytes = -5, quotaBytes = 2_000)!!, 0.0001f)
    }

    @Test
    fun `initial is the first whole character, upper-cased`() {
        assertEquals("A", initialOf("  ayesha"))
        assertEquals("", initialOf("   "))
        assertEquals("ع", initialOf("عائشہ"))
        // A character outside the basic plane is kept whole, never split in half.
        assertEquals("𝐀", initialOf("𝐀bc"))
    }

    @Test
    fun `fingerprint is grouped in fours`() {
        assertEquals("ab12 cd34 ef", groupedFingerprint("ab12cd34ef"))
    }

    @Test
    fun `server can be changed only while signed out`() {
        val signedOutCustom = SettingsUiState(isLoading = false, server = server(isDefault = false))
        assertTrue(signedOutCustom.canChooseServer)
        assertTrue(signedOutCustom.canUseBuiltInServer)

        val signedOutDefault = SettingsUiState(isLoading = false, server = server(isDefault = true))
        assertTrue(signedOutDefault.canChooseServer)
        assertFalse(signedOutDefault.canUseBuiltInServer)

        val signedInCustom = signedOutCustom.copy(user = user)
        assertFalse(signedInCustom.canChooseServer)
        assertFalse(signedInCustom.canUseBuiltInServer)
    }
}
