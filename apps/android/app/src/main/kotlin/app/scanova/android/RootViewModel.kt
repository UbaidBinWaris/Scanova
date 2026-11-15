package app.scanova.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.data.auth.AuthRepository
import app.scanova.core.data.backup.BackupRepository
import app.scanova.core.data.documents.DocumentRepository
import app.scanova.core.data.model.AuthState
import app.scanova.core.data.pairing.PairingRepository
import app.scanova.core.data.settings.SettingsRepository
import app.scanova.core.data.storage.LocalDataCleaner
import app.scanova.core.security.keys.VaultUnavailableException
import app.scanova.core.security.keys.VaultUnrecoverableException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which part of the app the user is allowed to see. */
enum class RootDestination {
    /** Still reading the encrypted state. */
    LOADING,

    /** First launch: what Scanova is. Shown once; no account needed afterwards. */
    WELCOME,

    /** Signed in with a temporary password that has to be replaced first. */
    PASSWORD_CHANGE,

    /** The app: library, scanning, settings. Works signed in (backup and sync) or not (on the phone only). */
    MAIN,

    /** The phone's stored keys could not be read. The user chooses: try again, or erase and start over. */
    VAULT_PROBLEM,
}

data class RootUiState(
    val destination: RootDestination = RootDestination.LOADING,
    /** With [RootDestination.VAULT_PROBLEM]: true when the keys are gone for good, false when it may pass. */
    val vaultKeysLost: Boolean = false,
    /** Someone is signed in (backup and sync on). */
    val signedIn: Boolean = false,
    /** After the welcome screen: open sign-in right away (the person said they have an account). */
    val openSignInOnStart: Boolean = false,
    /** A `scanova://pair?…` link the app was opened with and has not used yet. */
    val pendingPairingCode: String? = null,
)

/**
 * Decides the top-level screen from two facts stored on the phone: is there a
 * paired server, and is there a session. Screens never navigate between these
 * areas themselves; they change the stored facts and this state follows.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val pairingRepository: PairingRepository,
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    private val backupRepository: BackupRepository,
    private val documentRepository: DocumentRepository,
    private val localDataCleaner: LocalDataCleaner,
) : ViewModel() {

    private val mutableState = MutableStateFlow(RootUiState())
    val state: StateFlow<RootUiState> = mutableState.asStateFlow()
    private var observation: Job? = null

    init {
        observe()
        viewModelScope.launch {
            authRepository.authState.collect { auth -> mutableState.update { it.copy(signedIn = auth !is AuthState.SignedOut) } }
        }
        // Before anything else: no plaintext share copy survives a restart, whatever screen follows.
        viewModelScope.launch {
            runCatching { localDataCleaner.deleteShareCopies() }
                .onFailure { ScanovaLog.warn(TAG, "share_copy_cleanup_failed", it) }
        }
    }

    /** Try reading the stored keys again, after a temporary Keystore failure. */
    fun onVaultRetry() {
        mutableState.update { it.copy(destination = RootDestination.LOADING, vaultKeysLost = false) }
        observe()
    }

    /**
     * The user chose to erase everything on this phone after the keys could not be
     * read. Only ever done on this explicit choice: an automatic wipe would turn a
     * passing Keystore error into permanent loss of scans that were never uploaded.
     */
    fun onVaultReset() {
        viewModelScope.launch {
            localDataCleaner.resetAfterKeyLoss()
            onVaultRetry()
        }
    }

    private fun observe() {
        observation?.cancel()
        observation = viewModelScope.launch {
            combine(settingsRepository.settings, authRepository.authState) { settings, auth ->
                when {
                    auth is AuthState.PasswordChangeRequired -> RootDestination.PASSWORD_CHANGE
                    // No pairing, no sign-in gate: the app works on the phone right away.
                    auth is AuthState.SignedOut && !settings.welcomeSeen -> RootDestination.WELCOME
                    else -> RootDestination.MAIN
                }
            }
                .retryWhen { cause, attempt ->
                    // Keystore hiccups (busy, still unlocking after boot) usually pass.
                    val retry = cause is VaultUnavailableException && attempt < VAULT_RETRIES
                    if (retry) delay(VAULT_RETRY_DELAY_MS * (attempt + 1))
                    retry
                }
                .catch { cause ->
                    if (cause !is VaultUnrecoverableException && cause !is VaultUnavailableException) throw cause
                    ScanovaLog.error(TAG, "local_keys_unreadable lost=${cause is VaultUnrecoverableException}")
                    mutableState.update {
                        it.copy(
                            destination = RootDestination.VAULT_PROBLEM,
                            vaultKeysLost = cause is VaultUnrecoverableException,
                        )
                    }
                }
                .collect { destination ->
                    val previous = mutableState.value.destination
                    mutableState.update { it.copy(destination = destination) }
                    if (destination == RootDestination.MAIN && previous != RootDestination.MAIN) onMainShown()
                }
        }
    }

    /** The welcome screen was dismissed: it is not shown again. */
    fun onWelcomeDone(openSignIn: Boolean) {
        mutableState.update { it.copy(openSignInOnStart = openSignIn) }
        viewModelScope.launch { settingsRepository.setWelcomeSeen() }
    }

    fun onSignInOnStartHandled() {
        mutableState.update { it.copy(openSignInOnStart = false) }
    }

    /** The activity was started or re-used with a pairing link. */
    fun onPairingLink(link: String) {
        mutableState.update { it.copy(pendingPairingCode = link) }
    }

    fun onPairingLinkConsumed() {
        mutableState.update { it.copy(pendingPairingCode = null) }
    }

    /**
     * Entering the app: tidy up, learn what the server allows (sign-up), and
     * back up anything waiting (only when signed in; the backup is automatic).
     */
    private fun onMainShown() {
        cleanUpLeftovers()
        viewModelScope.launch { pairingRepository.refreshServerInfo() }
        backupRepository.backUpNow()
    }

    /** Removes temporary share copies and files orphaned by an interrupted scan. */
    private fun cleanUpLeftovers() {
        viewModelScope.launch {
            try {
                documentRepository.cleanUpLeftovers()
            } catch (e: Exception) {
                ScanovaLog.warn(TAG, "start_up_cleanup_failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "Root"
        const val VAULT_RETRIES = 3L
        const val VAULT_RETRY_DELAY_MS = 1_000L
    }
}
