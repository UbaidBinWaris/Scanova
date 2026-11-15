package app.scanova.core.security

import app.scanova.core.security.keys.DatabasePassphraseStore
import app.scanova.core.security.keys.TinkKeysets
import app.scanova.core.security.keys.VaultUnrecoverableException
import app.scanova.core.security.store.EncryptedKeyValueStore
import app.scanova.core.security.store.EncryptedTokenStore
import app.scanova.core.security.store.StoredTokens
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KeysAndStoresTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val tokens = StoredTokens(
        accessToken = "access-token-value",
        accessTokenExpiresAtEpochMs = 1_800_000_000_000,
        refreshToken = "refresh-token-value",
        refreshTokenExpiresAtEpochMs = 1_900_000_000_000,
    )

    @Test
    fun `keysets survive a restart and are stored wrapped`() {
        val dir = temp.newFolder("keys")
        val master = newInMemoryMasterKey()

        val ciphertext = TinkKeysets(dir, master).aead.encrypt("hello".toByteArray(), "aad".toByteArray())
        val reopened = TinkKeysets(dir, master).aead

        assertEquals("hello", reopened.decrypt(ciphertext, "aad".toByteArray()).toString(Charsets.UTF_8))
        // A wrapped keyset is an AEAD ciphertext; a raw Tink keyset would contain
        // the key type URL in the clear.
        val stored = File(dir, "aead.keyset").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(stored.contains("type.googleapis.com"))
    }

    @Test
    fun `aead rejects wrong associated data`() {
        val aead = TinkKeysets(temp.newFolder("keys"), newInMemoryMasterKey()).aead
        val ciphertext = aead.encrypt("hello".toByteArray(), "context-a".toByteArray())

        assertThrows(java.security.GeneralSecurityException::class.java) {
            aead.decrypt(ciphertext, "context-b".toByteArray())
        }
    }

    @Test
    fun `keysets wrapped by a lost master key are reported as unrecoverable`() {
        val dir = temp.newFolder("keys")
        TinkKeysets(dir, newInMemoryMasterKey()).aead

        assertThrows(VaultUnrecoverableException::class.java) {
            TinkKeysets(dir, newInMemoryMasterKey()).aead
        }
    }

    @Test
    fun `database key is stable, wrapped and in raw-key notation`() {
        val file = File(temp.newFolder("keys"), "db.key")
        val master = newInMemoryMasterKey()

        val first = DatabasePassphraseStore(file, master).rawKeySpec()
        val second = DatabasePassphraseStore(file, master).rawKeySpec()

        assertArrayEquals(first, second)
        val text = first.toString(Charsets.US_ASCII)
        assertTrue(Regex("x'[0-9a-f]{64}'").matches(text))
        // 1 version byte + 12 IV... the wrapped file must not contain the hex key.
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(text.substring(2, 66)))
    }

    @Test
    fun `two installs get different database keys`() {
        val a = DatabasePassphraseStore(File(temp.newFolder("a"), "db.key"), newInMemoryMasterKey()).rawKeySpec()
        val b = DatabasePassphraseStore(File(temp.newFolder("b"), "db.key"), newInMemoryMasterKey()).rawKeySpec()

        assertNotEquals(a.toString(Charsets.US_ASCII), b.toString(Charsets.US_ASCII))
    }

    @Test
    fun `key value store persists, updates and removes values`() = runTest {
        val file = File(temp.newFolder("state"), "values.enc")
        val aead = TinkKeysets(temp.newFolder("keys"), newInMemoryMasterKey()).aead

        val store = EncryptedKeyValueStore(file, aead)
        store.put("server.url", "https://scanova.home.arpa")
        store.edit {
            it["a"] = "1"
            it["b"] = "2"
        }
        store.remove("a")

        val reopened = EncryptedKeyValueStore(file, aead)
        assertEquals("https://scanova.home.arpa", reopened.get("server.url"))
        assertNull(reopened.get("a"))
        assertEquals("2", reopened.observe("b").first())
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("scanova.home.arpa"))
    }

    @Test
    fun `token store round trips and never prints tokens`() = runTest {
        val file = File(temp.newFolder("state"), "values.enc")
        val aead = TinkKeysets(temp.newFolder("keys"), newInMemoryMasterKey()).aead
        val store = EncryptedTokenStore(EncryptedKeyValueStore(file, aead))

        assertNull(store.current())
        assertFalse(store.observeSignedIn().first())

        store.save(tokens)

        val reopened = EncryptedTokenStore(EncryptedKeyValueStore(file, aead))
        assertEquals(tokens, reopened.current())
        assertTrue(reopened.observeSignedIn().first())
        assertFalse(tokens.toString().contains("token-value"))
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("refresh-token-value"))

        reopened.clear()
        assertNull(EncryptedTokenStore(EncryptedKeyValueStore(file, aead)).current())
    }

    @Test
    fun `only transient Keystore errors are retryable, never a wipe`() {
        val busy = app.scanova.core.security.keys.vaultFailure(
            java.security.GeneralSecurityException("unwrap", java.security.UnrecoverableKeyException("keystore busy")),
        )
        assertTrue(busy is app.scanova.core.security.keys.VaultUnavailableException)
        val wrongKey = app.scanova.core.security.keys.vaultFailure(javax.crypto.AEADBadTagException("tag mismatch"))
        assertTrue(wrongKey is VaultUnrecoverableException)
        val missing = app.scanova.core.security.keys.vaultFailure(
            app.scanova.core.security.keystore.MasterKeyMissingException(),
        )
        assertTrue(missing is VaultUnrecoverableException)
    }

    @Test
    fun `values encrypted under another key are reported as unrecoverable`() {
        val file = File(temp.newFolder("state"), "values.enc")
        EncryptedKeyValueStore(file, TinkKeysets(temp.newFolder("k1"), newInMemoryMasterKey()).aead).put("k", "v")

        val other = EncryptedKeyValueStore(file, TinkKeysets(temp.newFolder("k2"), newInMemoryMasterKey()).aead)

        assertThrows(VaultUnrecoverableException::class.java) { other.get("k") }
    }
}
