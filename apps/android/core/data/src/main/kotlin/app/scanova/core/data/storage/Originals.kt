package app.scanova.core.data.storage

import app.scanova.core.common.util.Sha256CountingOutputStream
import app.scanova.core.security.file.EncryptedFileStore
import java.io.OutputStream

/** A page's stored original: its file and the SHA-256 / size of its plaintext. */
internal data class StoredOriginal(
    val file: String,
    val sha256: String,
    val sizeBytes: Long,
)

/**
 * Makes sure page [pageId] has an original and describes it. An original
 * stored by the scan (or import) is used as it is; otherwise the scan's working
 * copy becomes the original, so no page saved from now on is without one.
 * Returns null when there is neither (the page then has no original).
 *
 * Blocking I/O: call on an I/O dispatcher.
 */
internal fun EncryptedFileStore.ensureOriginal(pageId: String): StoredOriginal? {
    val name = FileNames.original(pageId)
    if (exists(name)) return describe(name)
    val scratch = FileNames.scratch(pageId)
    if (!exists(scratch)) return null
    // Files are bound to their name, so "moving" one means re-encrypting it.
    val (size, sha) = write(name) { encrypted ->
        val counting = Sha256CountingOutputStream(encrypted)
        openRead(scratch).use { it.copyTo(counting) }
        counting.flush()
        counting.byteCount to counting.sha256Hex()
    }
    return StoredOriginal(name, sha, size)
}

/** Hash and size of an existing original, read without keeping it in memory. */
internal fun EncryptedFileStore.describe(name: String): StoredOriginal {
    val counting = Sha256CountingOutputStream(Discard)
    openRead(name).use { it.copyTo(counting) }
    return StoredOriginal(name, counting.sha256Hex(), counting.byteCount)
}

/** Counts and hashes without storing (`OutputStream.nullOutputStream()` needs API 33). */
private object Discard : OutputStream() {
    override fun write(b: Int) = Unit

    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

/** Copies encrypted file [from] to [to] (re-encrypting it under its new name). */
internal fun EncryptedFileStore.copy(from: String, to: String) {
    write(to) { output -> openRead(from).use { it.copyTo(output) } }
}
