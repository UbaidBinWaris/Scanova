package app.scanova.core.network.auth

import app.scanova.core.network.api.NO_AUTH_HEADER
import app.scanova.core.security.store.TokenStore
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Bearer <access token>` to every call except the ones
 * the API interface marks as public (login, logout, server-info).
 */
internal class AuthInterceptor(
    private val tokenStore: TokenStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder().removeHeader(NO_AUTH_HEADER)
        if (original.header(NO_AUTH_HEADER) == null) {
            tokenStore.current()?.let { tokens ->
                builder.header(AUTHORIZATION, bearer(tokens.accessToken))
            }
        }
        return chain.proceed(builder.build())
    }

    internal companion object {
        const val AUTHORIZATION = "Authorization"

        fun bearer(accessToken: String): String = "Bearer $accessToken"
    }
}
