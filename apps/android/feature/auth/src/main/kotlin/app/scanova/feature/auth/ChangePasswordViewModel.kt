package app.scanova.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class NewPasswordProblem { TOO_SHORT, SAME_AS_CURRENT }

data class ChangePasswordUiState(
    val currentPassword: String = "",
    val newPassword: String = "",
    val confirmation: String = "",
    val currentPasswordMissing: Boolean = false,
    val currentPasswordWrong: Boolean = false,
    val newPasswordProblem: NewPasswordProblem? = null,
    val confirmationMismatch: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: AppError? = null,
    /** Set once the server accepted the change. */
    val changed: Boolean = false,
)

/**
 * State holder for the password-change screen, used both when the server
 * forces a change (temporary password) and when the user chooses to change it.
 */
@HiltViewModel
class ChangePasswordViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(ChangePasswordUiState())
    val state: StateFlow<ChangePasswordUiState> = mutableState.asStateFlow()

    fun onCurrentPasswordChange(value: String) {
        mutableState.update {
            it.copy(currentPassword = value, currentPasswordMissing = false, currentPasswordWrong = false, error = null)
        }
    }

    fun onNewPasswordChange(value: String) {
        mutableState.update { it.copy(newPassword = value, newPasswordProblem = null, error = null) }
    }

    fun onConfirmationChange(value: String) {
        mutableState.update { it.copy(confirmation = value, confirmationMismatch = false, error = null) }
    }

    fun onSubmit() {
        val current = mutableState.value
        if (current.isSubmitting) return
        val currentMissing = current.currentPassword.isEmpty()
        val problem = when {
            current.newPassword.length < MIN_PASSWORD_LENGTH -> NewPasswordProblem.TOO_SHORT
            current.newPassword == current.currentPassword -> NewPasswordProblem.SAME_AS_CURRENT
            else -> null
        }
        val mismatch = current.confirmation != current.newPassword
        if (currentMissing || problem != null || mismatch) {
            mutableState.update {
                it.copy(
                    currentPasswordMissing = currentMissing,
                    newPasswordProblem = problem,
                    confirmationMismatch = mismatch,
                )
            }
            return
        }
        mutableState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            val result = authRepository.changePassword(current.currentPassword, current.newPassword)
            mutableState.update {
                when (result) {
                    is AppResult.Success -> ChangePasswordUiState(changed = true)
                    is AppResult.Failure -> {
                        val wrongCurrent = (result.error as? AppError.Api)?.code == ApiErrorCode.INVALID_CREDENTIALS
                        it.copy(
                            isSubmitting = false,
                            currentPasswordWrong = wrongCurrent,
                            error = result.error.takeUnless { wrongCurrent },
                        )
                    }
                }
            }
        }
    }

    fun onSignOut() {
        viewModelScope.launch { authRepository.signOut() }
    }

    private companion object {
        const val MIN_PASSWORD_LENGTH = 12
    }
}
