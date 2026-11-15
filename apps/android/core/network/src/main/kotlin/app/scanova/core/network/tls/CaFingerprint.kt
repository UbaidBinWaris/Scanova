package app.scanova.core.network.tls

import app.scanova.core.common.util.constantTimeEquals
import app.scanova.core.common.util.hexToBytesOrNull
import app.scanova.core.common.util.sha256
import app.scanova.core.common.util.toHex
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The pairing fingerprint: SHA-256 over the DER encoding of the server's CA
 * certificate, written as hex (docs/ARCHITECTURE.md §8, step 6).
 */
object CaFingerprint {
    private const val SHA256_BYTES = 32

    /**
     * Normalises what a person typed or a QR code carried: separators and case
     * are ignored. Returns 64 lower-case hex digits, or null if it is not a
     * SHA-256 fingerprint.
     */
    fun normalize(input: String): String? {
        val bytes = input.hexToBytesOrNull() ?: return null
        return if (bytes.size == SHA256_BYTES) bytes.toHex() else null
    }

    fun of(certificate: X509Certificate): String = sha256(certificate.encoded).toHex()

    /**
     * Parses a certificate file (PEM or DER) and returns it only if its
     * fingerprint equals [expectedFingerprint]. Anything else — unparseable
     * data, a different certificate, a malformed fingerprint — yields null, so a
     * caller cannot trust a certificate by forgetting to compare.
     */
    fun verify(certificateFile: ByteArray, expectedFingerprint: String): X509Certificate? {
        val expected = expectedFingerprint.hexToBytesOrNull() ?: return null
        if (expected.size != SHA256_BYTES) return null
        val certificate = parse(certificateFile) ?: return null
        val actual = sha256(certificate.encoded)
        return if (constantTimeEquals(actual, expected)) certificate else null
    }

    fun parse(certificateFile: ByteArray): X509Certificate? =
        try {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificateFile)) as? X509Certificate
        } catch (e: CertificateException) {
            null
        }
}
