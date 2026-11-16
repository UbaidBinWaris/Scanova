package app.scanova.core.designsystem.component

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.common.result.AppError
import app.scanova.core.designsystem.R

/**
 * The one place an [AppError] becomes a sentence. The text is the app's own,
 * translated wording: server-supplied messages are never shown, because they
 * are English-only and outside the app's control.
 */
@StringRes
fun AppError.messageRes(): Int = when (this) {
    AppError.NoConnection -> R.string.core_designsystem_error_no_connection
    AppError.LocalNetworkBlocked -> R.string.core_designsystem_error_local_network_blocked
    AppError.UntrustedServer -> R.string.core_designsystem_error_untrusted_server
    AppError.FingerprintMismatch -> R.string.core_designsystem_error_fingerprint_mismatch
    AppError.InvalidServerAddress -> R.string.core_designsystem_error_invalid_server_address
    AppError.NotPaired -> R.string.core_designsystem_error_not_paired
    AppError.SignedOut -> R.string.core_designsystem_error_signed_out
    AppError.LocalStorage -> R.string.core_designsystem_error_local_storage
    AppError.NotFound -> R.string.core_designsystem_error_not_found
    AppError.UnexpectedResponse -> R.string.core_designsystem_error_unexpected_response
    is AppError.Unexpected -> R.string.core_designsystem_error_unexpected
    is AppError.Api -> when (code) {
        ApiErrorCode.VALIDATION_FAILED -> R.string.core_designsystem_error_validation_failed
        ApiErrorCode.UNAUTHENTICATED,
        ApiErrorCode.INVALID_REFRESH_TOKEN,
        ApiErrorCode.SESSION_REVOKED,
        -> R.string.core_designsystem_error_signed_out
        ApiErrorCode.INVALID_CREDENTIALS -> R.string.core_designsystem_error_invalid_credentials
        ApiErrorCode.ACCOUNT_DISABLED -> R.string.core_designsystem_error_account_disabled
        ApiErrorCode.ADMIN_WEB_ONLY -> R.string.core_designsystem_error_admin_web_only
        ApiErrorCode.PASSWORD_CHANGE_REQUIRED -> R.string.core_designsystem_error_password_change_required
        ApiErrorCode.FORBIDDEN -> R.string.core_designsystem_error_forbidden
        ApiErrorCode.NOT_FOUND -> R.string.core_designsystem_error_not_found
        ApiErrorCode.ID_CONFLICT -> R.string.core_designsystem_error_id_conflict
        ApiErrorCode.FILE_TOO_LARGE -> R.string.core_designsystem_error_file_too_large
        ApiErrorCode.QUOTA_EXCEEDED -> R.string.core_designsystem_error_quota_exceeded
        ApiErrorCode.UNSUPPORTED_MEDIA_TYPE,
        ApiErrorCode.CHECKSUM_MISMATCH,
        -> R.string.core_designsystem_error_upload_rejected
        ApiErrorCode.RATE_LIMITED -> R.string.core_designsystem_error_rate_limited
        ApiErrorCode.SERVICE_UNAVAILABLE -> R.string.core_designsystem_error_service_unavailable
        ApiErrorCode.NOT_ENOUGH_MEMORY -> R.string.core_designsystem_error_not_enough_memory
        ApiErrorCode.AI_NOT_READY -> R.string.core_designsystem_error_ai_not_ready
        ApiErrorCode.INTERNAL_ERROR,
        ApiErrorCode.UNKNOWN,
        -> R.string.core_designsystem_error_server
    }
}

@Composable
fun AppError.message(): String = stringResource(messageRes())
