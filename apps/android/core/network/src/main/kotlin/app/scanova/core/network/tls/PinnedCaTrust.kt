package app.scanova.core.network.tls

import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS trust that accepts exactly one certificate authority: the one verified
 * at pairing. The system's CA store is not consulted, so neither a public CA
 * nor a CA installed on the phone can vouch for a server.
 *
 * Certificate chains are still validated in full by the platform's standard
 * trust manager (signatures, validity dates, name constraints); only the set
 * of trust anchors is replaced. Hostname verification stays OkHttp's default.
 */
class PinnedCaTrust private constructor(
    val trustManager: X509TrustManager,
    val socketFactory: SSLSocketFactory,
) {
    companion object {
        fun forCertificate(ca: X509Certificate): PinnedCaTrust {
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("scanova-paired-ca", ca)
            }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore)
            }
            val trustManager = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
            val context = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), null)
            }
            return PinnedCaTrust(trustManager, context.socketFactory)
        }
    }
}
