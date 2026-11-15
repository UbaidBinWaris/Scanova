package app.scanova.feature.auth

import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.common.result.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignUpLogicTest {

    private val creating = SignUpUiState(availability = SignUpAvailability.OPEN, isCreating = true)

    @Test
    fun `an e-mail address that is already registered marks the e-mail field`() {
        val state = creating.withSignUpFailure(AppError.Api(ApiErrorCode.ID_CONFLICT, httpStatus = 409))
        assertTrue(state.emailTaken)
        assertFalse(state.isCreating)
        assertNull(state.error)
        assertEquals(SignUpAvailability.OPEN, state.availability)
    }

    @Test
    fun `sign-up turned off or missing on the server shows the closed state`() {
        listOf(ApiErrorCode.FORBIDDEN to 403, ApiErrorCode.NOT_FOUND to 404).forEach { (code, status) ->
            val state = creating.withSignUpFailure(AppError.Api(code, httpStatus = status))
            assertEquals(SignUpAvailability.CLOSED, state.availability)
            assertFalse(state.isCreating)
            assertNull(state.error)
        }
    }

    @Test
    fun `other failures keep the form and show a banner`() {
        val state = creating.withSignUpFailure(AppError.NoConnection)
        assertEquals(AppError.NoConnection, state.error)
        assertEquals(SignUpAvailability.OPEN, state.availability)
        assertFalse(state.emailTaken)
        assertFalse(state.isCreating)
    }

    @Test
    fun `e-mail check accepts ordinary addresses and rejects obvious mistakes`() {
        assertTrue(SignUpViewModel.isPlausibleEmail("ayesha@example.com"))
        assertTrue(SignUpViewModel.isPlausibleEmail("  ayesha@example.com  "))
        assertFalse(SignUpViewModel.isPlausibleEmail("ayesha@example"))
        assertFalse(SignUpViewModel.isPlausibleEmail("ayesha example.com"))
        assertFalse(SignUpViewModel.isPlausibleEmail(""))
    }

    @Test
    fun `server authority hides the default https port and keeps others`() {
        assertEquals("api.example.com", serverAuthority("https://api.example.com"))
        assertEquals("api.example.com", serverAuthority("https://api.example.com:443/"))
        assertEquals("scanova.home.arpa:8443", serverAuthority("https://scanova.home.arpa:8443"))
    }
}
