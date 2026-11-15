package app.scanova.core.security

import app.scanova.core.security.file.EncryptedFileStore
import app.scanova.core.security.keys.TinkKeysets
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedFileStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun newStore(root: File = temp.newFolder("vault"), keys: File = temp.newFolder("keys")) =
        EncryptedFileStore(root, TinkKeysets(keys, newInMemoryMasterKey()).streamingAead)

    @Test
    fun `round trips a file larger than one segment`() {
        val store = newStore()
        // 2.5 MiB crosses two 1 MiB segment boundaries.
        val content = Random(7).nextBytes(2_621_440)

        store.writeBytes("pdf/doc-1.pdf", content)

        assertArrayEquals(content, store.readBytes("pdf/doc-1.pdf"))
    }

    @Test
    fun `round trips an empty file`() {
        val store = newStore()

        store.writeBytes("pages/empty.jpg", ByteArray(0))

        assertEquals(0, store.readBytes("pages/empty.jpg").size)
    }

    @Test
    fun `ciphertext on disk does not contain the plaintext`() {
        val root = temp.newFolder("vault")
        val store = newStore(root)
        val marker = "SCANOVA-PLAINTEXT-MARKER".toByteArray()

        store.writeBytes("pages/p1.jpg", marker + ByteArray(4096))

        val onDisk = File(root, "pages/p1.jpg").readBytes()
        assertFalse(onDisk.toString(Charsets.ISO_8859_1).contains("SCANOVA-PLAINTEXT-MARKER"))
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val root = temp.newFolder("vault")
        val store = newStore(root)
        store.writeBytes("pages/p1.jpg", Random(1).nextBytes(10_000))

        val file = File(root, "pages/p1.jpg")
        val bytes = file.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        assertThrows(IOException::class.java) { store.readBytes("pages/p1.jpg") }
    }

    @Test
    fun `truncated ciphertext is rejected`() {
        val root = temp.newFolder("vault")
        val store = newStore(root)
        store.writeBytes("pages/p1.jpg", Random(2).nextBytes(10_000))

        val file = File(root, "pages/p1.jpg")
        file.writeBytes(file.readBytes().copyOf(5_000))

        assertThrows(IOException::class.java) { store.readBytes("pages/p1.jpg") }
    }

    @Test
    fun `a file copied over another name is rejected`() {
        val root = temp.newFolder("vault")
        val store = newStore(root)
        store.writeBytes("pages/a.jpg", "page A".toByteArray())
        store.writeBytes("pages/b.jpg", "page B".toByteArray())

        File(root, "pages/a.jpg").copyTo(File(root, "pages/b.jpg"), overwrite = true)

        assertThrows(IOException::class.java) { store.readBytes("pages/b.jpg") }
    }

    @Test
    fun `a failed write leaves the previous version untouched`() {
        val root = temp.newFolder("vault")
        val store = newStore(root)
        store.writeBytes("pdf/doc.pdf", "version 1".toByteArray())

        assertThrows(IllegalStateException::class.java) {
            store.write("pdf/doc.pdf") { out ->
                out.write("version 2 (partial)".toByteArray())
                error("generator crashed")
            }
        }

        assertEquals("version 1", store.readBytes("pdf/doc.pdf").toString(Charsets.UTF_8))
        assertEquals(listOf("doc.pdf"), File(root, "pdf").list()?.toList())
    }

    @Test
    fun `a failed first write leaves no file`() {
        val store = newStore()

        assertThrows(IllegalStateException::class.java) {
            store.write("pdf/new.pdf") { error("generator crashed") }
        }

        assertFalse(store.exists("pdf/new.pdf"))
        assertThrows(FileNotFoundException::class.java) { store.openRead("pdf/new.pdf") }
    }

    @Test
    fun `files written with one keyset cannot be read with another`() {
        val root = temp.newFolder("vault")
        newStore(root, temp.newFolder("keys-a")).writeBytes("pages/p.jpg", "secret".toByteArray())

        val other = newStore(root, temp.newFolder("keys-b"))

        assertThrows(IOException::class.java) { other.readBytes("pages/p.jpg") }
    }

    @Test
    fun `delete and list reflect the store`() {
        val store = newStore()
        store.writeBytes("pages/a.jpg", byteArrayOf(1))
        store.writeBytes("pages/b.jpg", byteArrayOf(2))

        assertEquals(setOf("pages/a.jpg", "pages/b.jpg"), store.list("pages").toSet())
        assertTrue(store.delete("pages/a.jpg"))
        assertTrue(store.delete("pages/a.jpg"))
        assertEquals(listOf("pages/b.jpg"), store.list("pages"))
    }

    @Test
    fun `names that escape the store are refused`() {
        val store = newStore()

        assertThrows(IllegalArgumentException::class.java) { store.writeBytes("../outside", byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { store.writeBytes("/etc/passwd", byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { store.exists("pages/../../x") }
    }
}
