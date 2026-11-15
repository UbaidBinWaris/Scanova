package app.scanova.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.auth.AuthRepository
import app.scanova.core.data.backup.BackupRepository
import app.scanova.core.data.backup.BackupStatus
import app.scanova.core.data.model.AuthState
import app.scanova.core.data.model.PairedServer
import app.scanova.core.data.model.User
import app.scanova.core.data.pairing.PairingRepository
import app.scanova.core.data.update.AppUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SettingsDialog { EDIT_NAME, SIGN_OUT, USE_BUILT_IN_SERVER }

data class SettingsUiState(
    /** True until the first sign-in state is known. */
    val isLoading: Boolean = true,
    /** The account, or null while signed out (the app works fully without one). */
    val user: User? = null,
    val server: PairedServer? = null,
    /** Read for the sign-out warning: documents not backed up yet are lost. */
    val backup: BackupStatus = BackupStatus(enabled = false, pendingCount = 0, lastBackupAt = null),
    val dialog: SettingsDialog? = null,
    val isWorking: Boolean = false,
    val error: AppError? = null,
) {
    val signedIn: Boolean get() = user != null

    /** Documents on this phone not in the account yet; lost on sign-out, so the dialog says so. */
    val pendingCount: Int get() = backup.pendingCount

    /** Another server may be chosen only while signed out (documents belong to the account). */
    val canChooseServer: Boolean get() = !signedIn

    /** Going back to the built-in server: signed out, and a custom server is set. */
    val canUseBuiltInServer: Boolean get() = !signedIn && server?.isDefault == false
}

/** State holder for the Settings screen (account details, server, about, sign out). */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val pairingRepository: PairingRepository,
    private val backupRepository: BackupRepository,
    private val appUpdater: AppUpdater,
) : ViewModel() {

    /** This build's version, shown under "About". */
    val appVersion: String get() = appUpdater.currentVersionName

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
                mutableState.update {
                    // Signing out ends any account action that was running.
                    it.copy(user = user, isLoading = false, isWorking = it.isWorking && user != null)
                }
            }
        }
        viewModelScope.launch {
            pairingRepository.pairedServer.collect { server -> mutableState.update { it.copy(server = server) } }
        }
        viewModelScope.launch {
            backupRepository.status
                .catch { /* The figures are informative; the screen keeps the last ones. */ }
                .collect { status -> mutableState.update { it.copy(backup = status) } }
        }
        // Fresh account and server details; what is cached is shown meanwhile.
        viewModelScope.launch {
            authRepository.refreshUser()
            pairingRepository.refreshServerInfo()
        }
    }

    /** Asks the server for a newer build; the app's update dialog shows the answer. */
    fun onCheckForUpdates() {
        appUpdater.requestCheck()
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

    /** Forgets the custom server. The repository refuses while signed in; documents are kept. */
    fun onUseBuiltInServerConfirmed() {
        mutableState.update { it.copy(dialog = null) }
        viewModelScope.launch {
            pairingRepository.unpair()
            pairingRepository.refreshServerInfo()
        }
    }

    fun onSignOutConfirmed() {
        mutableState.update { it.copy(dialog = null, isWorking = true) }
        // The app's root reacts to the session ending; the screen then shows the signed-out layout.
        viewModelScope.launch { authRepository.signOut() }
    }
}
