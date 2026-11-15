package app.scanova.core.network.pairing

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A validated server location. Only HTTPS is accepted; `http://` input is
 * rejected rather than upgraded, so a typo can never downgrade the connection.
 */
class ServerAddress private constructor(
    /** Scheme, host, optional port and optional path prefix, without a trailing slash. */
    val baseUrl: String,
) {
    /** Base for Retrofit: `<baseUrl>/api/v1/`. */
    val apiBaseUrl: HttpUrl
        get() = "$baseUrl/api/v1/".toHttpUrlOrNull() ?: error("Validated at construction")

    /** Where the edge serves the internal CA certificate. */
    val caCertificateUrl: HttpUrl
        get() = "$baseUrl/.well-known/scanova/ca.crt".toHttpUrlOrNull() ?: error("Validated at construction")

    override fun equals(other: Any?): Boolean = other is ServerAddress && other.baseUrl == baseUrl

    override fun hashCode(): Int = baseUrl.hashCode()

    override fun toString(): String = baseUrl

    companion object {
        /**
         * Accepts `https://host`, `https://host:port/prefix` and, as a
         * convenience for typing, a bare `host` or `host:port`.
         */
        fun parse(input: String): ServerAddress? {
            val trimmed = input.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            val withScheme = when {
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                "://" in trimmed -> return null
                else -> "https://$trimmed"
            }
            val url = withScheme.toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            if (url.query != null || url.fragment != null) return null
            val normalized = url.newBuilder().build().toString().trimEnd('/')
            return ServerAddress(normalized)
        }
    }
}
