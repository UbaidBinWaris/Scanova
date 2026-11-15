package app.scanova.core.network

import app.scanova.core.network.pairing.ServerAddress
import app.scanova.core.network.tls.CaFingerprint
import app.scanova.core.security.store.EncryptedKeyValueStore
import java.security.cert.X509Certificate
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The server this phone is paired with. [caCertificate] is the only authority
 * trusted for that server; it is null when the server uses a publicly trusted
 * certificate, in which case the system trust store applies.
 */
class ServerConfig(
    val address: ServerAddress,
    val caCertificate: X509Certificate?,
) {
    val caFingerprint: String? get() = caCertificate?.let(CaFingerprint::of)

    override fun equals(other: Any?): Boolean =
        other is ServerConfig && other.address == address && other.caCertificate == caCertificate

    override fun hashCode(): Int = 31 * address.hashCode() + (caCertificate?.hashCode() ?: 0)
}

/** The server built into the app. Used until someone picks another one in Settings. */
object DefaultServer {
    const val URL = "https://api.scanova.ubaidbinwaris.com"

    /** Public certificate (Let's Encrypt): the system trust store, no pinned CA. */
    val config: ServerConfig = ServerConfig(requireNotNull(ServerAddress.parse(URL)), null)
}

/**
 * The server this app talks to: a custom server chosen in Settings (stored in
 * the encrypted key-value store), or [DefaultServer] when none was chosen.
 */
class ServerConfigStore(
    private val store: EncryptedKeyValueStore,
) {
    fun current(): ServerConfig? = resolve(store.get(KEY_URL), store.get(KEY_CA))

    fun observe(): Flow<ServerConfig?> =
        store.observeAll()
            .map { values -> resolve(values[KEY_URL], values[KEY_CA]) }
            .distinctUntilChanged()

    /** True while a custom server is chosen instead of [DefaultServer]. */
    fun isCustom(): Boolean = store.get(KEY_URL) != null

    fun observeIsCustom(): Flow<Boolean> = store.observeAll().map { it[KEY_URL] != null }.distinctUntilChanged()

    // No custom server: the built-in one. A custom server whose stored entry no
    // longer decodes stays null instead: never send its session to another host.
    private fun resolve(url: String?, caBase64: String?): ServerConfig? =
        if (url == null) DefaultServer.config else decode(url, caBase64)

    fun save(config: ServerConfig) {
        store.edit { values ->
            val ca = config.caCertificate
            if (ca == null) values.remove(KEY_CA) else values[KEY_CA] = Base64.getEncoder().encodeToString(ca.encoded)
            values[KEY_URL] = config.address.baseUrl
        }
    }

    fun clear() {
        store.edit { values ->
            values.remove(KEY_URL)
            values.remove(KEY_CA)
        }
    }

    private fun decode(url: String?, caBase64: String?): ServerConfig? {
        val address = url?.let(ServerAddress::parse) ?: return null
        if (caBase64 == null) return ServerConfig(address, null)
        // A stored CA that no longer parses must not silently fall back to the
        // system trust store: treat the phone as unpaired instead.
        val der = runCatching { Base64.getDecoder().decode(caBase64) }.getOrNull() ?: return null
        val certificate = CaFingerprint.parse(der) ?: return null
        return ServerConfig(address, certificate)
    }

    private companion object {
        const val KEY_URL = "server.url"
        const val KEY_CA = "server.ca"
    }
}
