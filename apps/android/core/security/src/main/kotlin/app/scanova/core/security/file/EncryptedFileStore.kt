package app.scanova.core.security.file

import com.google.crypto.tink.StreamingAead
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Files on disk that only ever exist encrypted (Tink streaming AEAD).
 *
 * - A file is addressed by a relative name such as `pages/<id>.jpg`. The name is
 *   bound into the ciphertext as associated data, so a file renamed or copied
 *   over another one fails authentication instead of being read as the other.
 * - A write goes to a temporary file that replaces the target only when the
 *   writing block returns normally. A crash, an exception or a cancelled
 *   coroutine never leaves a partial file under the real name.
 * - Content is streamed: a 200-page PDF costs the same memory as one page.
 */
class EncryptedFileStore(
    private val root: File,
    private val streamingAead: StreamingAead,
) {
    /**
     * Writes [name] through [block]. The file appears (replacing any previous
     * version) only if [block] completes; otherwise nothing changes on disk.
     */
    inline fun <T> write(name: String, block: (OutputStream) -> T): T {
        val pending = beginWrite(name)
        val result = try {
            block(pending.stream)
        } catch (e: Throwable) {
            pending.abort()
            throw e
        }
        pending.commit()
        return result
    }

    /** Opens [name] for reading. Bytes are released only after they authenticate. */
    fun openRead(name: String): InputStream {
        val file = resolve(name)
        if (!file.isFile) throw FileNotFoundException("No such encrypted file")
        val fileIn = file.inputStream()
        return try {
            streamingAead.newDecryptingStream(fileIn, associatedData(name))
        } catch (e: Exception) {
            fileIn.close()
            throw e
        }
    }

    fun writeBytes(name: String, bytes: ByteArray) {
        write(name) { it.write(bytes) }
    }

    fun readBytes(name: String): ByteArray = openRead(name).use { it.readBytes() }

    fun exists(name: String): Boolean = resolve(name).isFile

    /** Returns true when the file is gone afterwards (including when it never existed). */
    fun delete(name: String): Boolean {
        val file = resolve(name)
        return !file.exists() || file.delete()
    }

    /** Names of the files directly inside [directory], relative to the store root. */
    fun list(directory: String): List<String> {
        val dir = resolve(directory)
        return dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(TEMP_SUFFIX) }
            ?.map { "$directory/${it.name}" }
            .orEmpty()
    }

    /** Deletes every file in the store. */
    fun deleteAll() {
        root.deleteRecursively()
    }

    @PublishedApi
    internal fun beginWrite(name: String): PendingWrite {
        val target = resolve(name)
        val parent = target.parentFile ?: throw IOException("Invalid file name")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create directory")
        val temp = File(parent, target.name + TEMP_SUFFIX)
        val fileOut = FileOutputStream(temp)
        val encrypting = try {
            // Tink closes the stream it is given; the shield keeps the file open
            // so it can be synced to storage before it is renamed into place.
            streamingAead.newEncryptingStream(CloseShield(fileOut), associatedData(name))
        } catch (e: Exception) {
            fileOut.close()
            temp.delete()
            throw e
        }
        return PendingWrite(encrypting, fileOut, temp, target)
    }

    private fun associatedData(name: String): ByteArray = "scanova:file:v1:$name".toByteArray(Charsets.UTF_8)

    private fun resolve(name: String): File {
        require(NAME_PATTERN.matches(name) && ".." !in name) { "Invalid file name" }
        return File(root, name)
    }

    /** A write in progress: either [commit] or [abort] must be called exactly once. */
    @PublishedApi
    internal class PendingWrite(
        val stream: OutputStream,
        private val fileOut: FileOutputStream,
        private val temp: File,
        private val target: File,
    ) {
        fun commit() {
            try {
                // Closing the encrypting stream writes the final authenticated segment.
                stream.close()
                fileOut.fd.sync()
                fileOut.close()
            } catch (e: Exception) {
                abort()
                throw e
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IOException("Cannot move file into place")
            }
        }

        fun abort() {
            runCatching { fileOut.close() }
            temp.delete()
        }
    }

    private class CloseShield(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

        override fun close() = out.flush()
    }

    private companion object {
        const val TEMP_SUFFIX = ".tmp"
        val NAME_PATTERN = Regex("[A-Za-z0-9_-][A-Za-z0-9._/-]{0,200}")
    }
}
