package app.scanova.core.security.keys

import app.scanova.core.common.util.toHex
import com.google.crypto.tink.Aead
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * Holds the SQLCipher key: 32 random bytes generated on first use and stored
 * only wrapped by the hardware-backed master key.
 */
class DatabasePassphraseStore(
    private val file: File,
    private val masterKey: Aead,
    private val random: SecureRandom = SecureRandom(),
) {
    /**
     * Returns the key in SQLCipher's raw-key notation (`x'<64 hex digits>'`),
     * which tells SQLCipher to use the bytes as the AES key directly instead of
     * stretching them with PBKDF2. The key is already uniformly random, so
     * stretching would only slow every database open down.
     */
    @Synchronized
    fun rawKeySpec(): ByteArray {
        val key = loadOrCreate()
        try {
            return "x'${key.toHex()}'".toByteArray(Charsets.US_ASCII)
        } finally {
            key.fill(0)
        }
    }

    private fun loadOrCreate(): ByteArray {
        if (file.exists()) {
            return try {
                masterKey.decrypt(file.readBytes(), ASSOCIATED_DATA)
            } catch (e: GeneralSecurityException) {
                throw vaultFailure(e)
            }
        }
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        val wrapped = masterKey.encrypt(key, ASSOCIATED_DATA)
        val parent = file.parentFile ?: throw IOException("Passphrase path has no parent")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create key directory")
        val temp = File(parent, file.name + ".tmp")
        temp.outputStream().use { out ->
            out.write(wrapped)
            out.fd.sync()
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("Cannot store database key")
        }
        return key
    }

    private companion object {
        const val KEY_BYTES = 32
        val ASSOCIATED_DATA = "scanova:db-key:v1".toByteArray(Charsets.UTF_8)
    }
}
