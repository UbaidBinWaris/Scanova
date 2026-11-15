package app.scanova.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.update.AppUpdater
import app.scanova.core.data.auth.AuthRepository
import app.scanova.core.data.documents.DocumentRepository
import app.scanova.core.data.model.AuthState
import app.scanova.core.data.model.PairedServer
import app.scanova.core.data.model.User
import app.scanova.core.data.pairing.PairingRepository
import app.scanova.core.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SettingsDialog { EDIT_NAME, SIGN_OUT }

data class SettingsUiState(
    val isLoading: Boolean = true,
    val user: User? = null,
    val server: PairedServer? = null,
    val appLockEnabled: Boolean = false,
    /** Documents not uploaded yet; they are lost on sign-out, so the dialog says so. */
    val pendingSyncCount: Int = 0,
    val dialog: SettingsDialog? = null,
    val isWorking: Boolean = false,
    val error: AppError? = null,
)

/** State holder for the settings screen. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val pairingRepository: PairingRepository,
    private val settingsRepository: SettingsRepository,
    private val documentRepository: DocumentRepository,
    private val appUpdater: AppUpdater,
) : ViewModel() {

    /** This build's version, shown under "About". */
    val appVersion: String get() = appUpdater.currentVersionName

    /** Asks the server for a newer build; the app's update dialog shows the answer. */
    fun onCheckForUpdates() {
        appUpdater.requestCheck()
    }

    private val mutableState = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.authState.collect { auth ->
                val user = when (auth) {
                    is AuthState.SignedIn -> auth.user
                    is AuthState.PasswordChangeRequired -> auth.user
                    AuthState.SignedOut -> null
                }
                mutableState.update { it.copy(user = user, isLoading = false) }
            }
        }
        viewModelScope.launch {
            pairingRepository.pairedServer.collect { server -> mutableState.update { it.copy(server = server) } }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                mutableState.update { it.copy(appLockEnabled = settings.appLockEnabled) }
            }
        }
        viewModelScope.launch {
            documentRepository.observePendingSyncCount().catch { emit(0) }.collect { count ->
                mutableState.update { it.copy(pendingSyncCount = count) }
            }
        }
        // Fresh storage figures and server details; what is cached is shown meanwhile.
        viewModelScope.launch {
            authRepository.refreshUser()
            pairingRepository.refreshServerInfo()
        }
    }

    fun onShowDialog(dialog: SettingsDialog) {
        mutableState.update { it.copy(dialog = dialog, error = null) }
    }

    fun onDismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun onNameChanged(name: String) {
        mutableState.update { it.copy(dialog = null, isWorking = true, error = null) }
        viewModelScope.launch {
            val result = authRepository.updateName(name)
            mutableState.update { it.copy(isWorking = false, error = (result as? AppResult.Failure)?.error) }
        }
    }

    /** Called after the owner has confirmed their identity (when turning the lock on). */
    fun onAppLockChanged(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAppLockEnabled(enabled) }
    }

    fun onSignOutConfirmed() {
        mutableState.update { it.copy(dialog = null, isWorking = true) }
        // The app's root reacts to the session ending; this screen goes away with it.
        viewModelScope.launch { authRepository.signOut() }
    }
}
