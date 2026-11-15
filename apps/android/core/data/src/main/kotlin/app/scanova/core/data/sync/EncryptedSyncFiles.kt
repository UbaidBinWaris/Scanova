package app.scanova.core.data.sync

import app.scanova.core.security.file.EncryptedFileStore
import java.io.InputStream
import java.io.OutputStream

/** [SyncFiles] on top of the phone's encrypted file store. */
internal class EncryptedSyncFiles(private val store: EncryptedFileStore) : SyncFiles {
    override fun exists(name: String): Boolean = store.exists(name)

    override fun open(name: String): InputStream = store.openRead(name)

    override fun <T> write(name: String, block: (OutputStream) -> T): T = store.write(name, block)

    override fun delete(name: String) {
        store.delete(name)
    }
}
