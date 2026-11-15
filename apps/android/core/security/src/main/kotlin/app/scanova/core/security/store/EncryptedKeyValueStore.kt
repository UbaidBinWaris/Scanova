package app.scanova.core.security.store

import app.scanova.core.security.keys.vaultFailure
import com.google.crypto.tink.Aead
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * A small string-to-string store whose file is one AEAD ciphertext. It replaces
 * what `SharedPreferences` would normally hold (session tokens, the paired
 * server, settings), none of which may sit on disk in the clear.
 *
 * The whole map is rewritten on each change, which is fine for a few dozen
 * short values and keeps the format trivially atomic (temp file + rename).
 */
class EncryptedKeyValueStore(
    private val file: File,
    private val aead: Aead,
) {
    private val lock = Any()
    private val state = MutableStateFlow<Map<String, String>?>(null)

    fun get(key: String): String? = snapshot()[key]

    /** Emits the current value of [key] and every later change. */
    fun observe(key: String): Flow<String?> = state
        .map { (it ?: snapshot())[key] }
        .distinctUntilChanged()

    /** Emits the whole store, now and on every change, as one consistent snapshot. */
    fun observeAll(): Flow<Map<String, String>> = state
        .map { it ?: snapshot() }
        .distinctUntilChanged()

    fun put(key: String, value: String) = edit { it[key] = value }

    fun remove(key: String) = edit { it.remove(key) }

    /** Applies several changes and writes the file once. */
    fun edit(change: (MutableMap<String, String>) -> Unit) {
        synchronized(lock) {
            val updated = snapshot().toMutableMap().also(change)
            persist(updated)
            state.value = updated
        }
    }

    /** Removes every value and the file itself. */
    fun clear() {
        synchronized(lock) {
            file.delete()
            state.value = emptyMap()
        }
    }

    private fun snapshot(): Map<String, String> {
        state.value?.let { return it }
        synchronized(lock) {
            state.value?.let { return it }
            val loaded = load()
            state.value = loaded
            return loaded
        }
    }

    private fun load(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        val plaintext = try {
            aead.decrypt(file.readBytes(), ASSOCIATED_DATA)
        } catch (e: GeneralSecurityException) {
            throw vaultFailure(e)
        }
        return try {
            JSON.decodeFromString(SERIALIZER, plaintext.toString(Charsets.UTF_8))
        } finally {
            plaintext.fill(0)
        }
    }

    private fun persist(values: Map<String, String>) {
        val parent = file.parentFile ?: throw IOException("Store path has no parent")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create store directory")
        val plaintext = JSON.encodeToString(SERIALIZER, values).toByteArray(Charsets.UTF_8)
        val ciphertext = try {
            aead.encrypt(plaintext, ASSOCIATED_DATA)
        } finally {
            plaintext.fill(0)
        }
        val temp = File(parent, file.name + ".tmp")
        temp.outputStream().use { out ->
            out.write(ciphertext)
            out.fd.sync()
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("Cannot store values")
        }
    }

    private companion object {
        val ASSOCIATED_DATA = "scanova:kv:v1".toByteArray(Charsets.UTF_8)
        val JSON = Json { ignoreUnknownKeys = true }
        val SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
