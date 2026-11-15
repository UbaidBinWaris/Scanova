package app.scanova.core.common.result

/**
 * Error codes the server can put in the `error.code` field of the response
 * envelope (docs/ARCHITECTURE.md §7.5). [UNKNOWN] covers codes added by a newer
 * server so an old app degrades instead of crashing.
 */
enum class ApiErrorCode {
    VALIDATION_FAILED,
    UNAUTHENTICATED,
    INVALID_CREDENTIALS,
    INVALID_REFRESH_TOKEN,
    SESSION_REVOKED,
    ACCOUNT_DISABLED,
    ADMIN_WEB_ONLY,
    PASSWORD_CHANGE_REQUIRED,
    FORBIDDEN,
    NOT_FOUND,
    ID_CONFLICT,
    FILE_TOO_LARGE,
    QUOTA_EXCEEDED,
    UNSUPPORTED_MEDIA_TYPE,
    CHECKSUM_MISMATCH,
    RATE_LIMITED,
    INTERNAL_ERROR,
    SERVICE_UNAVAILABLE,
    UNKNOWN,
    ;

    companion object {
        fun fromWire(code: String?): ApiErrorCode =
            entries.firstOrNull { it != UNKNOWN && it.name == code } ?: UNKNOWN
    }
}
