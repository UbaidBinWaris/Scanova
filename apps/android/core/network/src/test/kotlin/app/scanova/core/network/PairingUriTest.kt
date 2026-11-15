package app.scanova.core.network

import app.scanova.core.network.pairing.PairingUri
import app.scanova.core.network.pairing.ServerAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PairingUriTest {

    private val fingerprint = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    @Test
    fun `parses the QR code printed by the server bootstrap`() {
        val uri = PairingUri.parse("scanova://pair?url=https%3A%2F%2Fscanova.home.arpa%3A8443&ca=$fingerprint")

        assertNotNull(uri)
        assertEquals("https://scanova.home.arpa:8443", uri!!.address.baseUrl)
        assertEquals(fingerprint, uri.caFingerprint)
        assertEquals("https://scanova.home.arpa:8443/api/v1/", uri.address.apiBaseUrl.toString())
        assertEquals(
            "https://scanova.home.arpa:8443/.well-known/scanova/ca.crt",
            uri.address.caCertificateUrl.toString(),
        )
    }

    @Test
    fun `fingerprint is optional for publicly trusted servers`() {
        val uri = PairingUri.parse("scanova://pair?url=https%3A%2F%2Fdocs.example.org")

        assertEquals("https://docs.example.org", uri?.address?.baseUrl)
        assertNull(uri?.caFingerprint)
    }

    @Test
    fun `fingerprint is normalised`() {
        val uri = PairingUri.parse("scanova://pair?url=https%3A%2F%2Fh.example&ca=${fingerprint.uppercase()}")

        assertEquals(fingerprint, uri?.caFingerprint)
    }

    @Test
    fun `rejects anything that is not a complete pairing code`() {
        val rejected = listOf(
            "",
            "https://scanova.home.arpa",
            "scanova://pair",
            "scanova://pair?ca=$fingerprint",
            "scanova://other?url=https%3A%2F%2Fh.example&ca=$fingerprint",
            "otherapp://pair?url=https%3A%2F%2Fh.example&ca=$fingerprint",
            // Cleartext servers are never accepted.
            "scanova://pair?url=http%3A%2F%2Fh.example&ca=$fingerprint",
            // A present but malformed fingerprint must not degrade to "no fingerprint".
            "scanova://pair?url=https%3A%2F%2Fh.example&ca=abc123",
            "scanova://pair?url=https%3A%2F%2Fh.example&ca=${fingerprint.dropLast(2)}",
            // Ambiguous: which URL would be used?
            "scanova://pair?url=https%3A%2F%2Fa.example&url=https%3A%2F%2Fb.example&ca=$fingerprint",
            "scanova://pair?url=%ZZ&ca=$fingerprint",
            "not a uri at all",
        )

        for (text in rejected) {
            assertNull("should reject: $text", PairingUri.parse(text))
        }
    }

    @Test
    fun `server address accepts https and bare hosts only`() {
        assertEquals("https://scanova.lan", ServerAddress.parse("https://scanova.lan/")?.baseUrl)
        assertEquals("https://scanova.lan", ServerAddress.parse("  scanova.lan ")?.baseUrl)
        assertEquals("https://192.168.1.20:8443", ServerAddress.parse("192.168.1.20:8443")?.baseUrl)
        assertEquals("https://h.example/scanova", ServerAddress.parse("https://h.example/scanova/")?.baseUrl)
        assertEquals("https://h.example", ServerAddress.parse("HTTPS://H.EXAMPLE")?.baseUrl)

        assertNull(ServerAddress.parse("http://scanova.lan"))
        assertNull(ServerAddress.parse("ftp://scanova.lan"))
        assertNull(ServerAddress.parse("https://user:pw@scanova.lan"))
        assertNull(ServerAddress.parse("https://scanova.lan/?x=1"))
        assertNull(ServerAddress.parse("scanova lan"))
        assertNull(ServerAddress.parse(""))
    }

    @Test
    fun `api and CA urls keep a path prefix`() {
        val address = ServerAddress.parse("https://h.example/scanova")!!

        assertEquals("https://h.example/scanova/api/v1/", address.apiBaseUrl.toString())
        assertEquals("https://h.example/scanova/.well-known/scanova/ca.crt", address.caCertificateUrl.toString())
    }
}
