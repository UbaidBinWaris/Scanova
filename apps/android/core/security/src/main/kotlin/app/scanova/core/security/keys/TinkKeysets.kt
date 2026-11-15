package app.scanova.core.security.keys

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.Parameters
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

/** The stored keys exist but can no longer be unwrapped (for example the Keystore key was lost). */
class VaultUnrecoverableException(cause: Throwable) : GeneralSecurityException("Stored keys cannot be unwrapped", cause)

/**
 * The Keystore failed for a reason that may pass (busy, still unlocking after boot,
 * a transient provider error). Nothing is lost; the operation can be retried.
 */
class VaultUnavailableException(cause: Throwable) : GeneralSecurityException("Secure storage is temporarily unavailable", cause)

/**
 * Sorts a failure to unwrap stored keys. Only the Android Keystore's own transient
 * errors (key entry unreadable, keystore busy, key could not be initialised) count as
 * temporary; those are retried and never lead to erasing anything. Every other
 * failure (missing master key, failed authentication tag, data that does not match
 * the key, a key the system invalidated for good) means the stored keys are lost.
 */
fun vaultFailure(error: GeneralSecurityException): GeneralSecurityException {
    if (error is VaultUnrecoverableException || error is VaultUnavailableException) return error
    val transient = generateSequence<Throwable>(error) { it.cause }.any { cause ->
        cause is java.security.UnrecoverableKeyException ||
            cause is java.security.KeyStoreException ||
            (
                cause is java.security.InvalidKeyException &&
                    cause !is android.security.keystore.KeyPermanentlyInvalidatedException
                )
    }
    return if (transient) VaultUnavailableException(error) else VaultUnrecoverableException(error)
}

/**
 * The app's working keys, kept as Tink keysets on disk and wrapped by the
 * hardware-backed master key:
 *
 * - [aead]: AES-256-GCM for small values (tokens, settings).
 * - [streamingAead]: AES-256-GCM-HKDF with 1 MiB segments for page images and
 *   PDFs, so a file is never held in memory whole.
 *
 * A keyset is created on first use, and unwrapped once per process.
 */
class TinkKeysets(
    private val directory: File,
    private val masterKey: Aead,
) {
    val aead: Aead by lazy {
        loadOrCreate(AEAD_FILE, PredefinedAeadParameters.AES256_GCM)
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    val streamingAead: StreamingAead by lazy {
        loadOrCreate(STREAMING_FILE, PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB)
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }

    @Synchronized
    private fun loadOrCreate(fileName: String, parameters: Parameters): KeysetHandle {
        val file = File(directory, fileName)
        val associatedData = "scanova:keyset:v1:$fileName".toByteArray(Charsets.UTF_8)
        if (file.exists()) {
            return try {
                TinkProtoKeysetFormat.parseEncryptedKeyset(file.readBytes(), masterKey, associatedData)
            } catch (e: GeneralSecurityException) {
                throw vaultFailure(e)
            }
        }
        val handle = KeysetHandle.generateNew(parameters)
        val wrapped = TinkProtoKeysetFormat.serializeEncryptedKeyset(handle, masterKey, associatedData)
        writeAtomically(file, wrapped)
        return handle
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw IOException("Keyset path has no parent")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create key directory")
        val temp = File(parent, target.name + ".tmp")
        temp.outputStream().use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Cannot store keyset")
        }
    }

    companion object {
        private const val AEAD_FILE = "aead.keyset"
        private const val STREAMING_FILE = "streaming.keyset"

        init {
            AeadConfig.register()
            StreamingAeadConfig.register()
        }
    }
}
