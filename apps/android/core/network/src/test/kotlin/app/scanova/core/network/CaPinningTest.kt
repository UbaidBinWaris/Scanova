package app.scanova.core.network

import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.common.util.sha256
import app.scanova.core.common.util.toHex
import app.scanova.core.network.tls.CaCertificateFetcher
import app.scanova.core.network.tls.CaFingerprint
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CaPinningTest {

    private val authority = TestAuthority("Home CA")
    private val otherAuthority = TestAuthority("Attacker CA")
    private val server = MockWebServer()

    private val fingerprint: String get() = sha256(authority.root.certificate.encoded).toHex()

    @After
    fun tearDown() {
        server.close()
    }

    // ── Fingerprint check ───────────────────────────────────────────────

    @Test
    fun `fingerprint is the SHA-256 of the certificate DER`() {
        assertEquals(fingerprint, CaFingerprint.of(authority.root.certificate))
        assertEquals(64, fingerprint.length)
    }

    @Test
    fun `verify accepts PEM and DER when the fingerprint matches`() {
        val pem = authority.root.certificatePem().toByteArray()
        val der = authority.root.certificate.encoded

        assertEquals(authority.root.certificate, CaFingerprint.verify(pem, fingerprint))
        assertEquals(authority.root.certificate, CaFingerprint.verify(der, fingerprint))
    }

    @Test
    fun `verify tolerates how people write fingerprints`() {
        val der = authority.root.certificate.encoded
        val spaced = fingerprint.uppercase().chunked(2).joinToString(":")
        val grouped = fingerprint.chunked(4).joinToString(" ")

        assertNotNull(CaFingerprint.verify(der, spaced))
        assertNotNull(CaFingerprint.verify(der, "  $grouped\n"))
        assertEquals(fingerprint, CaFingerprint.normalize(spaced))
    }

    @Test
    fun `verify rejects a different certificate`() {
        assertNull(CaFingerprint.verify(otherAuthority.root.certificate.encoded, fingerprint))
    }

    @Test
    fun `verify rejects a fingerprint that differs in one digit`() {
        val last = fingerprint.last()
        val altered = fingerprint.dropLast(1) + (if (last == '0') '1' else '0')

        assertNull(CaFingerprint.verify(authority.root.certificate.encoded, altered))
    }

    @Test
    fun `verify rejects malformed fingerprints and malformed certificates`() {
        val der = authority.root.certificate.encoded

        assertNull(CaFingerprint.verify(der, ""))
        assertNull(CaFingerprint.verify(der, "not hex"))
        assertNull(CaFingerprint.verify(der, fingerprint.take(40))) // a SHA-1 sized value
        assertNull(CaFingerprint.verify(der, fingerprint + "00"))
        assertNull(CaFingerprint.verify("garbage".toByteArray(), fingerprint))
        assertNull(CaFingerprint.verify(ByteArray(0), fingerprint))
        assertNull(CaFingerprint.normalize(fingerprint.take(62)))
    }

    // ── Downloading the CA during pairing ───────────────────────────────

    private fun fetcher(blocked: Boolean = false) = CaCertificateFetcher({ blocked })

    private fun caResponse(body: ByteArray): MockResponse =
        MockResponse.Builder().code(200).body(Buffer().write(body)).build()

    @Test
    fun `pairing download returns the CA when the fingerprint matches`() {
        server.startHttps(authority)
        server.enqueue(caResponse(authority.root.certificatePem().toByteArray()))

        val result = fetcher().fetchVerified(server.address().caCertificateUrl, fingerprint)

        assertEquals(authority.root.certificate, (result as AppResult.Success).value)
        val request = server.takeRequest()
        assertEquals("/.well-known/scanova/ca.crt", request.target)
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["Cookie"])
    }

    @Test
    fun `pairing download rejects a CA that does not match the fingerprint`() {
        // Someone on the network answers with their own CA: it must not be trusted.
        server.startHttps(otherAuthority)
        server.enqueue(caResponse(otherAuthority.root.certificatePem().toByteArray()))

        val result = fetcher().fetchVerified(server.address().caCertificateUrl, fingerprint)

        assertEquals(AppError.FingerprintMismatch, (result as AppResult.Failure).error)
    }

    @Test
    fun `pairing download rejects content that is not a certificate`() {
        server.startHttps(authority)
        server.enqueue(caResponse("<html>router login</html>".toByteArray()))
        server.enqueue(MockResponse.Builder().code(404).body("nope").build())
        server.enqueue(caResponse(ByteArray(200_000) { 'A'.code.toByte() }))

        val url = server.address().caCertificateUrl
        assertEquals(AppError.UnexpectedResponse, (fetcher().fetchVerified(url, fingerprint) as AppResult.Failure).error)
        assertEquals(AppError.UnexpectedResponse, (fetcher().fetchVerified(url, fingerprint) as AppResult.Failure).error)
        assertEquals(AppError.UnexpectedResponse, (fetcher().fetchVerified(url, fingerprint) as AppResult.Failure).error)
    }

    @Test
    fun `pairing download does not follow redirects`() {
        server.startHttps(authority)
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "https://example.org/ca.crt").build())

        val result = fetcher().fetchVerified(server.address().caCertificateUrl, fingerprint)

        assertEquals(AppError.UnexpectedResponse, (result as AppResult.Failure).error)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `pairing download reports an unreachable server`() {
        server.startHttps(authority)
        val url = server.address().caCertificateUrl
        server.close()

        assertEquals(AppError.NoConnection, (fetcher().fetchVerified(url, fingerprint) as AppResult.Failure).error)
        assertEquals(
            AppError.LocalNetworkBlocked,
            (fetcher(blocked = true).fetchVerified(url, fingerprint) as AppResult.Failure).error,
        )
    }

    // ── Trust after pairing ─────────────────────────────────────────────

    @Test
    fun `a server certified by the paired CA is trusted`() = runTest {
        server.startHttps(authority)
        server.enqueue(json(200, """{"data":{"name":"Home","apiVersion":"1","serverVersion":"1.0.0"},"error":null}"""))

        val result = remoteFor(ServerConfig(server.address(), authority.root.certificate)).serverInfo()

        assertEquals("Home", (result as AppResult.Success).value.name)
    }

    @Test
    fun `a server certified by any other CA is refused before a request is sent`() = runTest {
        server.startHttps(otherAuthority)
        server.enqueue(json(200, """{"data":{"name":"Evil","apiVersion":"1","serverVersion":"1.0.0"},"error":null}"""))

        val result = remoteFor(
            ServerConfig(server.address(), authority.root.certificate),
            InMemoryTokenStore(storedTokens("access-1", "refresh-1")),
        ).me()

        assertEquals(AppError.UntrustedServer, (result as AppResult.Failure).error)
        // The handshake failed, so neither the request nor the bearer token left the phone.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a private CA is not trusted when the pairing says to use the system store`() = runTest {
        server.startHttps(authority)
        server.enqueue(json(200, """{"data":{"name":"Home","apiVersion":"1","serverVersion":"1.0.0"},"error":null}"""))

        val result = remoteFor(ServerConfig(server.address(), caCertificate = null)).serverInfo()

        assertEquals(AppError.UntrustedServer, (result as AppResult.Failure).error)
    }

    @Test
    fun `a certificate for another host name is refused`() = runTest {
        server.startHttps(authority)
        // The certificate covers "localhost" and 127.0.0.1 only.
        val address = app.scanova.core.network.pairing.ServerAddress.parse("https://[::1]:${server.port}")!!

        val result = remoteFor(ServerConfig(address, authority.root.certificate)).serverInfo()

        val error = (result as AppResult.Failure).error
        // Either the name check fails (UntrustedServer) or the host does not
        // listen on IPv6 at all (NoConnection); it must never succeed.
        assertEquals(true, error == AppError.UntrustedServer || error == AppError.NoConnection)
    }
}
