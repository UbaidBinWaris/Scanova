package app.scanova.core.data.sync

import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.common.result.AppError

/**
 * Stores the reason of a failed upload in the database as a short code, and
 * reads it back. Only codes are stored, never messages, so the column cannot
 * leak anything about the document.
 */
internal object SyncErrorCodec {
    private const val API_PREFIX = "API/"

    fun encode(error: AppError): String = when (error) {
        is AppError.Api -> "$API_PREFIX${error.code.name}/${error.httpStatus}"
        AppError.NoConnection -> "NO_CONNECTION"
        AppError.LocalNetworkBlocked -> "LOCAL_NETWORK_BLOCKED"
        AppError.UntrustedServer -> "UNTRUSTED_SERVER"
        AppError.FingerprintMismatch -> "FINGERPRINT_MISMATCH"
        AppError.InvalidServerAddress -> "INVALID_SERVER_ADDRESS"
        AppError.NotPaired -> "NOT_PAIRED"
        AppError.SignedOut -> "SIGNED_OUT"
        AppError.LocalStorage -> "LOCAL_STORAGE"
        AppError.NotFound -> "NOT_FOUND"
        AppError.UnexpectedResponse -> "UNEXPECTED_RESPONSE"
        is AppError.Unexpected -> "UNEXPECTED"
    }

    fun decode(code: String?): AppError? = when {
        code == null -> null
        code.startsWith(API_PREFIX) -> {
            val parts = code.removePrefix(API_PREFIX).split('/')
            AppError.Api(
                code = ApiErrorCode.fromWire(parts.getOrNull(0)),
                httpStatus = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            )
        }
        code == "NO_CONNECTION" -> AppError.NoConnection
        code == "LOCAL_NETWORK_BLOCKED" -> AppError.LocalNetworkBlocked
        code == "UNTRUSTED_SERVER" -> AppError.UntrustedServer
        code == "FINGERPRINT_MISMATCH" -> AppError.FingerprintMismatch
        code == "INVALID_SERVER_ADDRESS" -> AppError.InvalidServerAddress
        code == "NOT_PAIRED" -> AppError.NotPaired
        code == "SIGNED_OUT" -> AppError.SignedOut
        code == "LOCAL_STORAGE" -> AppError.LocalStorage
        code == "NOT_FOUND" -> AppError.NotFound
        code == "UNEXPECTED_RESPONSE" -> AppError.UnexpectedResponse
        else -> AppError.Unexpected("stored")
    }
}

/** How the sync engine should react to a failed call. */
internal enum class FailureKind {
    /** The session is not usable: stop, the user has to sign in (or change the password). */
    AUTH,

    /** Nothing is wrong with the document; the same call can succeed later. */
    TRANSIENT,

    /** The server will keep refusing this document. */
    PERMANENT,
}

internal fun AppError.failureKind(): FailureKind = when (this) {
    AppError.SignedOut, AppError.NotPaired -> FailureKind.AUTH
    AppError.NoConnection, AppError.LocalNetworkBlocked, AppError.UntrustedServer -> FailureKind.TRANSIENT
    // A server that answers nonsense is misconfigured or mid-upgrade, not a verdict on the document.
    AppError.UnexpectedResponse, is AppError.Unexpected -> FailureKind.TRANSIENT
    AppError.LocalStorage, AppError.NotFound -> FailureKind.PERMANENT
    AppError.FingerprintMismatch, AppError.InvalidServerAddress -> FailureKind.PERMANENT
    is AppError.Api -> when (code) {
        ApiErrorCode.UNAUTHENTICATED,
        ApiErrorCode.INVALID_REFRESH_TOKEN,
        ApiErrorCode.SESSION_REVOKED,
        ApiErrorCode.ACCOUNT_DISABLED,
        ApiErrorCode.PASSWORD_CHANGE_REQUIRED,
        ApiErrorCode.ADMIN_WEB_ONLY,
        ApiErrorCode.INVALID_CREDENTIALS,
        -> FailureKind.AUTH
        ApiErrorCode.RATE_LIMITED,
        ApiErrorCode.INTERNAL_ERROR,
        ApiErrorCode.SERVICE_UNAVAILABLE,
        ApiErrorCode.NOT_ENOUGH_MEMORY,
        -> FailureKind.TRANSIENT
        ApiErrorCode.UNKNOWN -> if (httpStatus >= 500) FailureKind.TRANSIENT else FailureKind.PERMANENT
        ApiErrorCode.VALIDATION_FAILED,
        ApiErrorCode.FORBIDDEN,
        ApiErrorCode.NOT_FOUND,
        ApiErrorCode.ID_CONFLICT,
        ApiErrorCode.FILE_TOO_LARGE,
        ApiErrorCode.QUOTA_EXCEEDED,
        ApiErrorCode.UNSUPPORTED_MEDIA_TYPE,
        ApiErrorCode.CHECKSUM_MISMATCH,
        -> FailureKind.PERMANENT
    }
}
