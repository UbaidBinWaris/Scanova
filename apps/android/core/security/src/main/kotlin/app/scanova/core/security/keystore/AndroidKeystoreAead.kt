package app.scanova.core.security.keystore

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.scanova.core.common.log.ScanovaLog
import com.google.crypto.tink.Aead
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The root of the phone's key hierarchy: an AES-256-GCM key generated inside the
 * Android Keystore and never exportable. It is kept in StrongBox (a dedicated
 * secure element) when the phone has one, otherwise in the TEE.
 *
 * It only ever wraps other keys (Tink keysets, the database passphrase), so it
 * is used a handful of times per app start and the slowness of secure hardware
 * does not matter.
 *
 * Wire format: `0x01 ‖ iv(12) ‖ ciphertext ‖ tag(16)`.
 */
class AndroidKeystoreAead(
    private val context: Context,
    private val alias: String = DEFAULT_ALIAS,
) : Aead {

    private val lock = Any()

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray = synchronized(lock) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        if (associatedData != null) cipher.updateAAD(associatedData)
        val iv = cipher.iv
        if (iv.size != IV_BYTES) throw GeneralSecurityException("Unexpected IV length")
        val body = cipher.doFinal(plaintext)
        ByteArray(1 + IV_BYTES + body.size).also { out ->
            out[0] = FORMAT_VERSION
            System.arraycopy(iv, 0, out, 1, IV_BYTES)
            System.arraycopy(body, 0, out, 1 + IV_BYTES, body.size)
        }
    }

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray = synchronized(lock) {
        if (ciphertext.size < 1 + IV_BYTES + TAG_BYTES || ciphertext[0] != FORMAT_VERSION) {
            throw GeneralSecurityException("Unsupported ciphertext format")
        }
        val key = existingKey() ?: throw MasterKeyMissingException()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, ciphertext, 1, IV_BYTES))
        if (associatedData != null) cipher.updateAAD(associatedData)
        cipher.doFinal(ciphertext, 1 + IV_BYTES, ciphertext.size - 1 - IV_BYTES)
    }

    /** Removes the key. Everything it wrapped becomes unrecoverable. */
    fun deleteKey() = synchronized(lock) {
        keyStore().deleteEntry(alias)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun getOrCreateKey(): SecretKey = existingKey() ?: generateKey()

    private fun generateKey(): SecretKey {
        if (hasStrongBox()) {
            try {
                return generate(strongBox = true)
            } catch (unavailable: ProviderException) {
                // StrongBoxUnavailableException is a ProviderException; some
                // devices advertise StrongBox and still fail with the parent type.
                ScanovaLog.warn(TAG, "strongbox_unavailable_falling_back_to_tee", unavailable)
            }
        }
        return generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(KEY_BITS)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .apply {
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(spec)
        return generator.generateKey()
    }

    private fun hasStrongBox(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    companion object {
        const val DEFAULT_ALIAS = "scanova_master_v1"
        private const val TAG = "KeystoreAead"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 0x01
        private const val KEY_BITS = 256
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16

    }
}

/** The Keystore has no master key under the alias: everything it wrapped is lost for good. */
class MasterKeyMissingException : GeneralSecurityException("Master key is missing")
