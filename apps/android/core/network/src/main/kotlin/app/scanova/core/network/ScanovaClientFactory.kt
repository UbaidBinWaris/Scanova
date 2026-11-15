package app.scanova.core.network

import app.scanova.core.network.api.ApiJson
import app.scanova.core.network.api.ScanovaApi
import app.scanova.core.network.auth.AuthInterceptor
import app.scanova.core.network.auth.SessionEvents
import app.scanova.core.network.auth.TokenAuthenticator
import app.scanova.core.network.tls.PinnedCaTrust
import app.scanova.core.security.store.TokenStore
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Builds the HTTP stack for one paired server. A new stack is built whenever
 * the pairing changes, because the trusted CA is fixed per client.
 */
internal class ScanovaClientFactory(
    private val tokenStore: TokenStore,
    private val sessionEvents: SessionEvents,
) {
    fun create(config: ServerConfig): ScanovaApi {
        val transport = OkHttpClient.Builder()
            // TLS only. Cleartext is also refused by the manifest's network security config.
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .apply {
                config.caCertificate?.let { ca ->
                    val trust = PinnedCaTrust.forCertificate(ca)
                    sslSocketFactory(trust.socketFactory, trust.trustManager)
                }
            }
            .build()

        val apiBaseUrl = config.address.apiBaseUrl
        val refreshUrl = apiBaseUrl.newBuilder().addPathSegments("auth/refresh").build()

        // Same connection pool and TLS setup, plus authentication.
        val authenticated = transport.newBuilder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .authenticator(TokenAuthenticator(tokenStore, sessionEvents, transport, refreshUrl))
            .build()

        return Retrofit.Builder()
            .baseUrl(apiBaseUrl)
            .client(authenticated)
            .addConverterFactory(ApiJson.asConverterFactory(JSON_MEDIA_TYPE))
            .build()
            .create(ScanovaApi::class.java)
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 60L
        const val WRITE_TIMEOUT_SECONDS = 60L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
