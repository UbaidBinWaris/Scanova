package app.scanova.core.common.util

import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest

fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

/** Comparison whose duration does not depend on where the inputs differ. */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

/**
 * Passes bytes through to [out] while counting them and hashing them with
 * SHA-256, so a file can be written once and still report its size and checksum.
 */
class Sha256CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    private val digest = MessageDigest.getInstance("SHA-256")

    var byteCount: Long = 0
        private set

    override fun write(b: Int) {
        out.write(b)
        digest.update(b.toByte())
        byteCount++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        digest.update(b, off, len)
        byteCount += len
    }

    /** Finishes the hash. Call once, after the last write. */
    fun sha256Hex(): String = digest.digest().toHex()
}
