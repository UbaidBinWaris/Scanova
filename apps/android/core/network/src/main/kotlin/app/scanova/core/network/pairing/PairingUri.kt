package app.scanova.core.network.pairing

import app.scanova.core.network.tls.CaFingerprint
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * The content of the pairing QR code printed by the server's bootstrap script:
 * `scanova://pair?url=<url-encoded server URL>&ca=<sha256 hex of the CA certificate DER>`.
 *
 * [caFingerprint] is absent when the server uses a publicly trusted
 * certificate (`SCANOVA_TLS_MODE=acme`).
 */
data class PairingUri(
    val address: ServerAddress,
    val caFingerprint: String?,
) {
    companion object {
        private const val SCHEME = "scanova"
        private const val HOST = "pair"

        /** Returns null for anything that is not a well-formed pairing code. */
        fun parse(text: String): PairingUri? {
            val uri = try {
                URI(text.trim())
            } catch (e: URISyntaxException) {
                return null
            }
            if (!SCHEME.equals(uri.scheme, ignoreCase = true) || !HOST.equals(uri.host, ignoreCase = true)) {
                return null
            }
            val parameters = parseQuery(uri.rawQuery ?: return null) ?: return null
            val address = ServerAddress.parse(parameters["url"] ?: return null) ?: return null
            val rawFingerprint = parameters["ca"]?.takeIf { it.isNotBlank() }
            val fingerprint = rawFingerprint?.let { CaFingerprint.normalize(it) ?: return null }
            return PairingUri(address, fingerprint)
        }

        private fun parseQuery(rawQuery: String): Map<String, String>? {
            val result = mutableMapOf<String, String>()
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val separator = pair.indexOf('=')
                if (separator <= 0) return null
                try {
                    val key = URLDecoder.decode(pair.substring(0, separator), "UTF-8")
                    val value = URLDecoder.decode(pair.substring(separator + 1), "UTF-8")
                    // A repeated parameter is ambiguous; refuse instead of guessing.
                    if (result.put(key, value) != null) return null
                } catch (e: IllegalArgumentException) {
                    return null
                }
            }
            return result
        }
    }
}
