package app.scanova.core.network

import app.scanova.core.network.api.ApiCallExecutor
import app.scanova.core.network.auth.SessionEvents
import app.scanova.core.network.pairing.ServerAddress
import app.scanova.core.security.store.StoredTokens
import app.scanova.core.security.store.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

internal class InMemoryTokenStore(initial: StoredTokens? = null) : TokenStore {
    private val state = MutableStateFlow(initial)

    override fun current(): StoredTokens? = state.value

    override fun save(tokens: StoredTokens) {
        state.value = tokens
    }

    override fun clear() {
        state.value = null
    }

    override fun observeSignedIn(): Flow<Boolean> = state.map { it != null }
}

/** A private certificate authority, like the one the server's bootstrap script creates. */
internal class TestAuthority(name: String = "Scanova Test CA") {
    val root: HeldCertificate = HeldCertificate.Builder()
        .certificateAuthority(0)
        .commonName(name)
        .build()

    /** TLS material for a server on localhost whose certificate this authority signed. */
    fun serverCertificates(): HandshakeCertificates {
        val leaf = HeldCertificate.Builder()
            .signedBy(root)
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        return HandshakeCertificates.Builder().heldCertificate(leaf).build()
    }
}

/** HTTPS mock server whose certificate chains to [authority]. */
internal fun MockWebServer.startHttps(authority: TestAuthority): MockWebServer = apply {
    useHttps(authority.serverCertificates().sslSocketFactory())
    start()
}

internal fun MockWebServer.address(): ServerAddress =
    ServerAddress.parse("https://localhost:$port") ?: error("mock server address must parse")

internal fun json(code: Int, body: String, vararg headers: Pair<String, String>): MockResponse =
    MockResponse.Builder()
        .code(code)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
        .body(body)
        .build()

internal fun errorEnvelope(status: Int, code: String, vararg headers: Pair<String, String>): MockResponse =
    json(status, """{"data":null,"error":{"code":"$code","message":"Server text that the app must not show."}}""", *headers)

internal const val USER_JSON =
    """{"id":"7b1c9a52-0000-4000-8000-000000000001","email":"member@example.org","name":"Member",""" +
        """"userType":"MEMBER","status":"ACTIVE","mustChangePassword":false,"storageUsedBytes":1024,""" +
        """"storageQuotaBytes":null,"createdAt":"2026-11-01T10:00:00.000Z"}"""

internal fun tokensJson(access: String, refresh: String): String =
    """{"data":{"accessToken":"$access","accessTokenExpiresAt":"2026-11-15T12:15:00.000Z",""" +
        """"refreshToken":"$refresh","refreshTokenExpiresAt":"2026-12-15T12:00:00.000Z","user":$USER_JSON},""" +
        """"error":null,"meta":{"nextCursor":null}}"""

internal fun storedTokens(access: String, refresh: String) = StoredTokens(
    accessToken = access,
    accessTokenExpiresAtEpochMs = 0,
    refreshToken = refresh,
    refreshTokenExpiresAtEpochMs = Long.MAX_VALUE,
)

/** The production client stack pointed at [config]. */
internal fun remoteFor(
    config: ServerConfig?,
    tokenStore: TokenStore = InMemoryTokenStore(),
    sessionEvents: SessionEvents = SessionEvents(),
    localNetworkBlocked: Boolean = false,
): ScanovaRemote = ScanovaRemote(
    currentConfig = { config },
    clientFactory = ScanovaClientFactory(tokenStore, sessionEvents),
    executor = ApiCallExecutor { localNetworkBlocked },
)
