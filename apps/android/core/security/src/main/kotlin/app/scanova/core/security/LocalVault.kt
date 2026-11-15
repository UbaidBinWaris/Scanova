package app.scanova.core.security

import app.scanova.core.security.file.EncryptedFileStore
import app.scanova.core.security.store.EncryptedKeyValueStore
import java.io.File

/**
 * Entry point for destroying local secrets, used when the user unpairs the
 * phone or when the stored keys turn out to be unreadable.
 */
class LocalVault(
    private val files: EncryptedFileStore,
    private val values: EncryptedKeyValueStore,
    private val keyDirectory: File,
    private val deleteMasterKey: () -> Unit,
) {
    /** Deletes every encrypted file (page images and PDFs). */
    fun deleteAllFiles() = files.deleteAll()

    /** Deletes tokens, the paired server and settings. */
    fun deleteAllValues() = values.clear()

    /**
     * Deletes the wrapped keysets, the wrapped database key and the Keystore
     * master key. Everything encrypted with them becomes unreadable for good;
     * new keys are generated the next time one is needed.
     */
    fun destroyKeys() {
        keyDirectory.deleteRecursively()
        runCatching(deleteMasterKey)
    }
}
