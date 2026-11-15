package app.scanova.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.auth.AuthRepository
import app.scanova.core.data.pairing.PairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Whether the server lets people create an account here. */
enum class SignUpAvailability {
    /** Asking the server; nothing cached says it is open. */
    CHECKING,
    OPEN,
    CLOSED,

    /** The server could not be asked and nothing cached says it is open. */
    UNREACHABLE,
}

data class SignUpUiState(
    /** The server's address, shown so people know where their backup goes. */
    val serverLabel: String = "",
    val isDefaultServer: Boolean = true,
    val availability: SignUpAvailability = SignUpAvailability.CHECKING,
    /** Why the server could not be asked, while [availability] is UNREACHABLE. */
    val checkError: AppError? = null,
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val nameMissing: Boolean = false,
    val emailInvalid: Boolean = false,
    /** The server already has an account with this e-mail address. */
    val emailTaken: Boolean = false,
    val passwordTooShort: Boolean = false,
    val isCreating: Boolean = false,
    val error: AppError? = null,
    /** Account created and signed in: the route closes itself. */
    val signedUp: Boolean = false,
)

/** State holder for creating an account (open sign-up). */
@HiltViewModel
class SignUpViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val pairingRepository: PairingRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(SignUpUiState())
    val state: StateFlow<SignUpUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            pairingRepository.pairedServer.collect { server ->
                mutableState.update {
                    it.copy(
                        serverLabel = server?.url?.let(::serverAuthority).orEmpty(),
                        isDefaultServer = server?.isDefault ?: true,
                    )
                }
            }
        }
        checkAvailability()
    }

    /**
     * Asks the server whether sign-up is open. A cached "open" shows the form
     * at once; otherwise the screen waits for the answer instead of flashing
     * "closed" on a first launch.
     */
    fun checkAvailability() {
        viewModelScope.launch {
            val cachedOpen = pairingRepository.pairedServer.first()?.signup == true
            mutableState.update {
                it.copy(
                    availability = if (cachedOpen) SignUpAvailability.OPEN else SignUpAvailability.CHECKING,
                    checkError = null,
                )
            }
            val result = pairingRepository.refreshServerInfo()
            mutableState.update {
                when (result) {
                    is AppResult.Success -> it.copy(
                        availability = if (result.value.signup) SignUpAvailability.OPEN else SignUpAvailability.CLOSED,
                    )
                    // Offline with a cached "open": keep the form; submitting will explain.
                    is AppResult.Failure -> if (it.availability == SignUpAvailability.OPEN) {
                        it
                    } else {
                        it.copy(availability = SignUpAvailability.UNREACHABLE, checkError = result.error)
                    }
                }
            }
        }
    }

    fun onNameChange(value: String) = mutableState.update { it.copy(name = value, nameMissing = false, error = null) }

    fun onEmailChange(value: String) =
        mutableState.update { it.copy(email = value, emailInvalid = false, emailTaken = false, error = null) }

    fun onPasswordChange(value: String) =
        mutableState.update { it.copy(password = value, passwordTooShort = false, error = null) }

    fun onCreateAccount() {
        val current = mutableState.value
        if (current.isCreating || current.availability != SignUpAvailability.OPEN) return
        val nameMissing = current.name.isBlank()
        val emailInvalid = !isPlausibleEmail(current.email)
        val passwordTooShort = current.password.length < MIN_PASSWORD_LENGTH
        if (nameMissing || emailInvalid || passwordTooShort) {
            mutableState.update {
                it.copy(nameMissing = nameMissing, emailInvalid = emailInvalid, passwordTooShort = passwordTooShort)
            }
            return
        }
        mutableState.update { it.copy(isCreating = true, error = null) }
        viewModelScope.launch {
            val result = authRepository.signUp(current.name, current.email, current.password)
            mutableState.update {
                when (result) {
                    is AppResult.Success -> it.copy(isCreating = false, password = "", signedUp = true)
                    is AppResult.Failure -> it.withSignUpFailure(result.error)
                }
            }
        }
    }

    companion object {
        /** Same minimum as the server (12 to 256 characters). */
        const val MIN_PASSWORD_LENGTH = 12
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        internal fun isPlausibleEmail(value: String): Boolean = EMAIL.matches(value.trim())
    }
}

/**
 * What a failed sign-up means for the form. The shared wording for these codes
 * talks about documents, so the ones sign-up can answer are handled here.
 */
internal fun SignUpUiState.withSignUpFailure(error: AppError): SignUpUiState {
    val code = (error as? AppError.Api)?.code
    return when (code) {
        // The e-mail address is already registered.
        ApiErrorCode.ID_CONFLICT -> copy(isCreating = false, emailTaken = true)
        // Sign-up was turned off, or the server does not offer it at all.
        ApiErrorCode.FORBIDDEN, ApiErrorCode.NOT_FOUND ->
            copy(isCreating = false, availability = SignUpAvailability.CLOSED)
        else -> copy(isCreating = false, error = error)
    }
}
