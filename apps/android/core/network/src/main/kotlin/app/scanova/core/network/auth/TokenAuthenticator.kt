package app.scanova.core.network.auth

import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.network.api.ApiJson
import app.scanova.core.network.api.Envelope
import app.scanova.core.network.api.RefreshTokenRequest
import app.scanova.core.network.api.TokensDto
import app.scanova.core.security.store.StoredTokens
import app.scanova.core.security.store.TokenStore
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route

/**
 * Reacts to `401` on an authenticated call by refreshing the session once and
 * replaying the call.
 *
 * Refresh tokens are single use, and the server revokes the whole session if a
 * spent one is presented again (docs/ARCHITECTURE.md §5.5). Several calls
 * usually fail at the same moment when the access token expires, so the
 * refresh is single-flight: one thread performs it under [lock]; the others
 * wait, see that the stored access token is no longer the one they sent, and
 * simply retry with the new one.
 */
internal class TokenAuthenticator(
    private val tokenStore: TokenStore,
    private val sessionEvents: SessionEvents,
    /** A client without this authenticator and without the auth interceptor. */
    private val refreshClient: OkHttpClient,
    private val refreshUrl: HttpUrl,
) : Authenticator {

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        val sentAuthorization = response.request.header(AuthInterceptor.AUTHORIZATION)
            // A 401 on a public call (wrong password on login) is not a session problem.
            ?: return null
        if (priorResponseCount(response) >= MAX_ATTEMPTS) return null

        when (errorCodeOf(response)) {
            // The access token expired or was not accepted: refresh below.
            ApiErrorCode.UNAUTHENTICATED -> Unit
            ApiErrorCode.SESSION_REVOKED -> {
                endSession(sentAuthorization)
                return null
            }
            // Any other 401 (for example a wrong current password) is the
            // call's own answer. Refreshing would not change it and replaying
            // it would count as another failed attempt on the server.
            else -> return null
        }

        synchronized(lock) {
            val stored = tokenStore.current() ?: return null
            val storedAuthorization = AuthInterceptor.bearer(stored.accessToken)
            if (storedAuthorization != sentAuthorization) {
                // Someone else refreshed while this call was in flight or waiting.
                return response.request.withAuthorization(storedAuthorization)
            }
            return when (val outcome = refresh(stored.refreshToken)) {
                is RefreshOutcome.Success -> {
                    // Signed out (or replaced) while the refresh was on the network: drop the result.
                    if (!tokenStore.replace(stored.refreshToken, outcome.tokens.toStored())) return null
                    sessionEvents.emit(SessionEvent.Refreshed(outcome.tokens.user))
                    response.request.withAuthorization(AuthInterceptor.bearer(outcome.tokens.accessToken))
                }
                RefreshOutcome.Rejected -> {
                    tokenStore.clear()
                    sessionEvents.emit(SessionEvent.Ended)
                    null
                }
                // Offline or server trouble: keep the session and let the caller see the 401.
                RefreshOutcome.Unavailable -> null
            }
        }
    }

    private fun endSession(sentAuthorization: String) {
        synchronized(lock) {
            val stored = tokenStore.current() ?: return
            // Only end the session the failed call belonged to, not a newer one.
            if (AuthInterceptor.bearer(stored.accessToken) == sentAuthorization) {
                tokenStore.clear()
                sessionEvents.emit(SessionEvent.Ended)
            }
        }
    }

    private fun refresh(refreshToken: String): RefreshOutcome {
        val body = ApiJson.encodeToString(RefreshTokenRequest.serializer(), RefreshTokenRequest(refreshToken))
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(refreshUrl).post(body).build()
        return try {
            refreshClient.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (response.isSuccessful) {
                    val tokens = ApiJson.decodeFromString<Envelope<TokensDto>>(text).data
                    if (tokens != null) RefreshOutcome.Success(tokens) else RefreshOutcome.Unavailable
                } else if (response.code == HTTP_UNAUTHORIZED || response.code == HTTP_FORBIDDEN) {
                    // INVALID_REFRESH_TOKEN, SESSION_REVOKED, ACCOUNT_DISABLED: the session is over.
                    RefreshOutcome.Rejected
                } else {
                    RefreshOutcome.Unavailable
                }
            }
        } catch (e: IOException) {
            RefreshOutcome.Unavailable
        } catch (e: SerializationException) {
            RefreshOutcome.Unavailable
        }
    }

    private fun errorCodeOf(response: Response): ApiErrorCode? =
        try {
            val text = response.peekBody(MAX_PEEK_BYTES).string()
            ApiJson.decodeFromString<Envelope<JsonElement>>(text).error?.code?.let(ApiErrorCode::fromWire)
        } catch (e: IOException) {
            null
        } catch (e: SerializationException) {
            null
        }

    private fun priorResponseCount(response: Response): Int {
        var count = 0
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private fun Request.withAuthorization(value: String): Request =
        newBuilder().header(AuthInterceptor.AUTHORIZATION, value).build()

    private sealed interface RefreshOutcome {
        data class Success(val tokens: TokensDto) : RefreshOutcome
        data object Rejected : RefreshOutcome
        data object Unavailable : RefreshOutcome
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
        const val MAX_PEEK_BYTES = 16L * 1024
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** Converts the wire tokens to what is stored. Unparseable expiry times become "expired now". */
fun TokensDto.toStored(): StoredTokens = StoredTokens(
    accessToken = accessToken,
    accessTokenExpiresAtEpochMs = accessTokenExpiresAt.toEpochMsOrZero(),
    refreshToken = refreshToken,
    refreshTokenExpiresAtEpochMs = refreshTokenExpiresAt.toEpochMsOrZero(),
)

private fun String.toEpochMsOrZero(): Long =
    try {
        Instant.parse(this).toEpochMilli()
    } catch (e: DateTimeParseException) {
        0L
    }
