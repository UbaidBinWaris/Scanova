package app.scanova.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.auth.AuthRepository
import app.scanova.core.data.pairing.PairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignInUiState(
    val serverLabel: String = "",
    val email: String = "",
    val password: String = "",
    val emailMissing: Boolean = false,
    val passwordMissing: Boolean = false,
    val isSigningIn: Boolean = false,
    val error: AppError? = null,
    val confirmingServerChange: Boolean = false,
)

/**
 * State holder for the sign-in screen. A successful sign-in stores the
 * session, which the app's root observes; this screen does not navigate.
 */
@HiltViewModel
class SignInViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val pairingRepository: PairingRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            pairingRepository.pairedServer.collect { server ->
                // Always the address the app actually talks to. The server's own name is
                // chosen by the server, so a look-alike server could claim to be "Scanova".
                mutableState.update { it.copy(serverLabel = server?.url?.let(::serverAuthority).orEmpty()) }
            }
        }
    }

    fun onEmailChange(value: String) {
        mutableState.update { it.copy(email = value, emailMissing = false, error = null) }
    }

    fun onPasswordChange(value: String) {
        mutableState.update { it.copy(password = value, passwordMissing = false, error = null) }
    }

    fun onSignIn() {
        val current = mutableState.value
        if (current.isSigningIn) return
        val emailMissing = current.email.isBlank()
        val passwordMissing = current.password.isEmpty()
        if (emailMissing || passwordMissing) {
            mutableState.update { it.copy(emailMissing = emailMissing, passwordMissing = passwordMissing) }
            return
        }
        mutableState.update { it.copy(isSigningIn = true, error = null) }
        viewModelScope.launch {
            val result = authRepository.signIn(current.email, current.password)
            mutableState.update {
                when (result) {
                    // The password is dropped from memory as soon as it is no longer needed.
                    is AppResult.Success -> it.copy(isSigningIn = false, password = "")
                    is AppResult.Failure -> it.copy(isSigningIn = false, error = result.error)
                }
            }
        }
    }

    fun onChangeServerRequested() {
        mutableState.update { it.copy(confirmingServerChange = true) }
    }

    fun onChangeServerDismissed() {
        mutableState.update { it.copy(confirmingServerChange = false) }
    }

    fun onChangeServerConfirmed() {
        mutableState.update { it.copy(confirmingServerChange = false) }
        viewModelScope.launch { pairingRepository.unpair() }
    }
}

/** `host` or `host:port` of the paired server's URL; the raw text if it cannot be parsed. */
internal fun serverAuthority(serverUrl: String): String {
    val uri = runCatching { java.net.URI(serverUrl.trim()) }.getOrNull()
    val host = uri?.host ?: return serverUrl.trim()
    return if (uri.port == -1 || uri.port == 443) host else "$host:${uri.port}"
}
