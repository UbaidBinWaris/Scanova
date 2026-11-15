package app.scanova.core.network.tls

import android.annotation.SuppressLint
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.network.api.LocalNetworkAccess
import app.scanova.core.network.api.isTlsTrustFailure
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads the server's CA certificate during pairing and returns it only if
 * it matches the fingerprint the user supplied.
 *
 * This is the one connection the app makes without a trusted certificate: the
 * CA is what is being fetched, so nothing can vouch for the connection yet.
 * That is safe because of what is and is not done over it:
 *
 * - Nothing secret is sent: no credentials, no tokens, no cookies; the request
 *   is a fixed GET.
 * - Nothing received is trusted until its SHA-256 equals the fingerprint, which
 *   reached the phone out of band (QR code or typed by the user). An attacker on
 *   the path can only make pairing fail, not make the phone trust another CA.
 * - The response is capped in size and redirects are not followed.
 *
 * The client built here is private to this class and never used for anything else.
 */
class CaCertificateFetcher(
    private val localNetworkAccess: LocalNetworkAccess,
    private val client: OkHttpClient = bootstrapClient(),
) {
    /** Blocking; call from an I/O dispatcher. */
    fun fetchVerified(caCertificateUrl: HttpUrl, expectedFingerprint: String): AppResult<X509Certificate> {
        val request = Request.Builder().url(caCertificateUrl).get().build()
        val bytes = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return AppResult.Failure(AppError.UnexpectedResponse)
                }
                val source = response.body.source()
                source.request(MAX_CERTIFICATE_BYTES + 1)
                if (source.buffer.size > MAX_CERTIFICATE_BYTES) {
                    return AppResult.Failure(AppError.UnexpectedResponse)
                }
                source.buffer.readByteArray()
            }
        } catch (e: IOException) {
            return AppResult.Failure(
                when {
                    e.isTlsTrustFailure() -> AppError.UntrustedServer
                    localNetworkAccess.isBlocked() -> AppError.LocalNetworkBlocked
                    else -> AppError.NoConnection
                },
            )
        }
        if (CaFingerprint.parse(bytes) == null) return AppResult.Failure(AppError.UnexpectedResponse)
        val certificate = CaFingerprint.verify(bytes, expectedFingerprint)
            ?: return AppResult.Failure(AppError.FingerprintMismatch)
        return AppResult.Success(certificate)
    }

    companion object {
        private const val MAX_CERTIFICATE_BYTES = 64L * 1024

        private fun bootstrapClient(): OkHttpClient {
            val trustManager = UnverifiedBootstrapTrustManager()
            val context = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), SecureRandom())
            }
            return OkHttpClient.Builder()
                .sslSocketFactory(context.socketFactory, trustManager)
                // The certificate is not trusted yet, so its names prove nothing either.
                .hostnameVerifier { _, _ -> true }
                .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * Accepts any server certificate. Used only by [bootstrapClient] to fetch a
     * file whose authenticity is then established by fingerprint instead of by
     * the TLS chain. See the class documentation for why this is sound.
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private class UnverifiedBootstrapTrustManager : X509TrustManager {
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        @SuppressLint("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
