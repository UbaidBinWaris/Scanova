package app.scanova.core.data.sync

import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.common.result.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FailureKindTest {

    private fun kind(code: ApiErrorCode, status: Int) = AppError.Api(code, status).failureKind()

    @Test
    fun `connectivity and server-side trouble are transient`() {
        assertEquals(FailureKind.TRANSIENT, AppError.NoConnection.failureKind())
        assertEquals(FailureKind.TRANSIENT, AppError.LocalNetworkBlocked.failureKind())
        assertEquals(FailureKind.TRANSIENT, AppError.UntrustedServer.failureKind())
        assertEquals(FailureKind.TRANSIENT, kind(ApiErrorCode.RATE_LIMITED, 429))
        assertEquals(FailureKind.TRANSIENT, kind(ApiErrorCode.INTERNAL_ERROR, 500))
        assertEquals(FailureKind.TRANSIENT, kind(ApiErrorCode.SERVICE_UNAVAILABLE, 503))
        assertEquals(FailureKind.TRANSIENT, kind(ApiErrorCode.UNKNOWN, 502))
    }

    @Test
    fun `session problems need the user`() {
        assertEquals(FailureKind.AUTH, AppError.SignedOut.failureKind())
        assertEquals(FailureKind.AUTH, AppError.NotPaired.failureKind())
        assertEquals(FailureKind.AUTH, kind(ApiErrorCode.UNAUTHENTICATED, 401))
        assertEquals(FailureKind.AUTH, kind(ApiErrorCode.SESSION_REVOKED, 401))
        assertEquals(FailureKind.AUTH, kind(ApiErrorCode.ACCOUNT_DISABLED, 403))
        assertEquals(FailureKind.AUTH, kind(ApiErrorCode.PASSWORD_CHANGE_REQUIRED, 403))
    }

    @Test
    fun `verdicts about one document are permanent`() {
        assertEquals(FailureKind.PERMANENT, kind(ApiErrorCode.QUOTA_EXCEEDED, 413))
        assertEquals(FailureKind.PERMANENT, kind(ApiErrorCode.FILE_TOO_LARGE, 413))
        assertEquals(FailureKind.PERMANENT, kind(ApiErrorCode.ID_CONFLICT, 409))
        assertEquals(FailureKind.PERMANENT, kind(ApiErrorCode.CHECKSUM_MISMATCH, 422))
        assertEquals(FailureKind.PERMANENT, kind(ApiErrorCode.VALIDATION_FAILED, 400))
        assertEquals(FailureKind.PERMANENT, kind(ApiErrorCode.UNKNOWN, 418))
        assertEquals(FailureKind.PERMANENT, AppError.LocalStorage.failureKind())
    }

    @Test
    fun `stored error codes round trip`() {
        val errors = listOf(
            AppError.Api(ApiErrorCode.QUOTA_EXCEEDED, 413),
            AppError.Api(ApiErrorCode.ID_CONFLICT, 409),
            AppError.NoConnection,
            AppError.LocalNetworkBlocked,
            AppError.UntrustedServer,
            AppError.LocalStorage,
            AppError.NotFound,
            AppError.SignedOut,
            AppError.UnexpectedResponse,
        )

        for (error in errors) {
            assertEquals(error, SyncErrorCodec.decode(SyncErrorCodec.encode(error)))
        }
        assertNull(SyncErrorCodec.decode(null))
    }

    @Test
    fun `an unreadable stored code degrades to a generic error`() {
        assertEquals(AppError.Unexpected("stored"), SyncErrorCodec.decode("SOMETHING_FROM_THE_FUTURE"))
        assertEquals(AppError.Api(ApiErrorCode.UNKNOWN, 0), SyncErrorCodec.decode("API/NEW_CODE/x"))
    }
}
