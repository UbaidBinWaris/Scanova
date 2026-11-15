package app.scanova.core.common.result

/**
 * Every way an operation can fail, in terms a screen can act on. None of the
 * variants carries document content, tokens or free-form server text, so an
 * [AppError] is always safe to log.
 */
sealed interface AppError {

    /** The server answered with an error envelope. */
    data class Api(
        val code: ApiErrorCode,
        val httpStatus: Int,
        /** Seconds from the `Retry-After` header, when the server sent one. */
        val retryAfterSeconds: Long? = null,
    ) : AppError

    /** The server could not be reached: no network, DNS failure or timeout. */
    data object NoConnection : AppError

    /**
     * Android is blocking traffic to the local network because the user has not
     * granted the local-network permission (Android 17 and later).
     */
    data object LocalNetworkBlocked : AppError

    /** TLS failed: the server's certificate is not signed by the paired CA. */
    data object UntrustedServer : AppError

    /** The CA certificate downloaded during pairing does not match the fingerprint. */
    data object FingerprintMismatch : AppError

    /** The server address or pairing code is not usable. */
    data object InvalidServerAddress : AppError

    /** No server has been paired yet. */
    data object NotPaired : AppError

    /** There is no valid session; the user has to sign in again. */
    data object SignedOut : AppError

    /** Reading or writing encrypted local data failed. */
    data object LocalStorage : AppError

    /** The requested local item does not exist (any more). */
    data object NotFound : AppError

    /** The response could not be understood (wrong shape, wrong server). */
    data object UnexpectedResponse : AppError

    /** Anything else. [kind] is an exception class name, never a message. */
    data class Unexpected(val kind: String) : AppError
}

/** True when trying again later can succeed without the user changing anything. */
val AppError.isRetryable: Boolean
    get() = when (this) {
        AppError.NoConnection -> true
        is AppError.Api -> httpStatus >= 500 || code == ApiErrorCode.RATE_LIMITED
        else -> false
    }
