package app.scanova.core.network.api

import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import okhttp3.ResponseBody
import retrofit2.Response

/** A page of a cursor-paginated list. [nextCursor] is null on the last page. */
data class Page<T>(
    val items: List<T>,
    val nextCursor: String?,
)

/** Tells whether Android is currently refusing local-network traffic for this app. */
fun interface LocalNetworkAccess {
    fun isBlocked(): Boolean
}

/**
 * Turns an HTTP exchange into an [AppResult]. This is the only place that
 * knows about the `{ data, error, meta }` envelope, HTTP statuses and
 * transport exceptions; everything above it sees typed results.
 */
internal class ApiCallExecutor(
    private val localNetworkAccess: LocalNetworkAccess,
) {
    suspend fun <T : Any> data(call: suspend () -> Response<Envelope<T>>): AppResult<T> =
        envelope(call) { envelope -> envelope.data }

    suspend fun <T : Any> page(call: suspend () -> Response<Envelope<List<T>>>): AppResult<Page<T>> =
        envelope(call) { envelope -> envelope.data?.let { Page(it, envelope.meta?.nextCursor) } }

    /** For endpoints that answer with raw bytes on success and an envelope on failure. */
    suspend fun <R> raw(call: suspend () -> Response<ResponseBody>, onBody: suspend (ResponseBody) -> R): AppResult<R> =
        guarded {
            val response = call()
            val body = response.body()
            if (response.isSuccessful && body != null) {
                body.use { AppResult.Success(onBody(it)) }
            } else {
                AppResult.Failure(errorFrom(response))
            }
        }

    private suspend fun <T, R : Any> envelope(
        call: suspend () -> Response<Envelope<T>>,
        extract: (Envelope<T>) -> R?,
    ): AppResult<R> = guarded {
        val response = call()
        val envelope = response.body()
        if (!response.isSuccessful || envelope == null) {
            return@guarded AppResult.Failure(errorFrom(response))
        }
        val error = envelope.error
        if (error != null) {
            return@guarded AppResult.Failure(AppError.Api(ApiErrorCode.fromWire(error.code), response.code()))
        }
        val value = extract(envelope) ?: return@guarded AppResult.Failure(AppError.UnexpectedResponse)
        AppResult.Success(value)
    }

    private suspend fun <R> guarded(block: suspend () -> AppResult<R>): AppResult<R> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            AppResult.Failure(AppError.UnexpectedResponse)
        } catch (e: IOException) {
            AppResult.Failure(transportError(e))
        } catch (e: RuntimeException) {
            AppResult.Failure(AppError.Unexpected(e.javaClass.simpleName))
        }

    private fun transportError(e: IOException): AppError = when {
        e.isTlsTrustFailure() -> AppError.UntrustedServer
        localNetworkAccess.isBlocked() -> AppError.LocalNetworkBlocked
        else -> AppError.NoConnection
    }

    private fun errorFrom(response: Response<*>): AppError {
        val status = response.code()
        val code = response.errorBody()?.use { body -> parseErrorCode(body) }
        val retryAfter = response.headers()["Retry-After"]?.trim()?.toLongOrNull()
        return when {
            code != null -> AppError.Api(ApiErrorCode.fromWire(code), status, retryAfter)
            // No envelope: a proxy answered instead of the API (it is down or restarting).
            status == 502 || status == 503 || status == 504 ->
                AppError.Api(ApiErrorCode.SERVICE_UNAVAILABLE, status, retryAfter)
            status >= 500 -> AppError.Api(ApiErrorCode.INTERNAL_ERROR, status, retryAfter)
            status == 429 -> AppError.Api(ApiErrorCode.RATE_LIMITED, status, retryAfter)
            // Anything else without an envelope is not a Scanova API.
            else -> AppError.UnexpectedResponse
        }
    }

    private fun parseErrorCode(body: ResponseBody): String? =
        try {
            // Error bodies are small; refuse to read an unbounded one into memory.
            val text = body.source().apply { request(MAX_ERROR_BODY_BYTES) }.buffer.snapshot().utf8()
            ApiJson.decodeFromString<Envelope<JsonElement>>(text).error?.code
        } catch (e: SerializationException) {
            null
        } catch (e: IOException) {
            null
        }

    private companion object {
        const val MAX_ERROR_BODY_BYTES = 64L * 1024
    }
}

/**
 * True when the connection failed because the server's certificate was not
 * trusted (unknown CA, or a certificate for another host name).
 *
 * The rejection is rarely the exception that surfaces:
 * - OkHttp tries every address a host name resolves to and throws the failure
 *   of the last attempt, attaching earlier ones as suppressed exceptions. A
 *   rejected certificate on the first address followed by "connection
 *   refused" on the second is still a trust failure.
 * - Coroutines may re-create an exception when it crosses a suspension point,
 *   keeping the original as its cause.
 *
 * So the whole tree of causes and suppressed exceptions is searched.
 */
internal fun IOException.isTlsTrustFailure(): Boolean = containsTrustFailure(this, depth = 0)

private const val MAX_EXCEPTION_DEPTH = 8

private fun containsTrustFailure(error: Throwable, depth: Int): Boolean {
    if (depth > MAX_EXCEPTION_DEPTH) return false
    if (error is SSLPeerUnverifiedException) return true
    // A handshake failure counts only when a certificate check caused it;
    // protocol mismatches and resets are connectivity problems.
    if (error is SSLHandshakeException && hasCertificateCause(error)) return true
    val cause = error.cause
    if (cause != null && cause !== error && containsTrustFailure(cause, depth + 1)) return true
    return error.suppressed.any { containsTrustFailure(it, depth + 1) }
}

private fun hasCertificateCause(error: Throwable): Boolean {
    var cause: Throwable? = error
    var depth = 0
    while (cause != null && depth <= MAX_EXCEPTION_DEPTH) {
        if (cause is CertificateException) return true
        cause = cause.cause
        depth++
    }
    return false
}
