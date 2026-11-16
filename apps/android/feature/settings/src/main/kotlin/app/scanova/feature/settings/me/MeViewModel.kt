package app.scanova.feature.settings.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.data.auth.AuthRepository
import app.scanova.core.data.backup.BackupRepository
import app.scanova.core.data.backup.BackupStatus
import app.scanova.core.data.settings.SettingsRepository
import app.scanova.core.data.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** State holder for the Me tab: account, backup, appearance, scanning defaults, app lock, screenshots. */
@HiltViewModel
internal class MeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val backupRepository: BackupRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(MeUiState())
    val state: StateFlow<MeUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val backup = backupRepository.status
                // The figures are informative; without them the tab still works.
                .catch { emit(BackupStatus(enabled = false, pendingCount = 0, lastBackupAt = null)) }
            combine(authRepository.authState, backup, settingsRepository.settings, ::meUiState)
                .catch { error ->
                    // The account and settings are read from this phone; show the defaults rather than spin forever.
                    ScanovaLog.error(TAG, "me_load_failed", error)
                    mutableState.update { it.copy(isLoading = false) }
                }
                .collect { content ->
                    // What the user is being told right now survives a refresh of the figures.
                    mutableState.update { content.copy(saveFailed = it.saveFailed, notice = it.notice) }
                }
        }
        // Fresh storage figures; the cached ones are shown meanwhile.
        viewModelScope.launch { authRepository.refreshUser() }
    }

    /** The whole app cross-fades to the new scheme. */
    fun onThemeModeChange(mode: ThemeMode) = save { settingsRepository.setThemeMode(mode) }

    fun onAutoCaptureChange(enabled: Boolean) = save { settingsRepository.setAutoCaptureEnabled(enabled) }

    fun onAutoEnhanceChange(enabled: Boolean) = save { settingsRepository.setAutoEnhance(enabled) }

    /** Called after the owner has confirmed their identity (when turning the lock on). */
    fun onAppLockChange(enabled: Boolean) = save { settingsRepository.setAppLockEnabled(enabled) }

    /** Keeps Scanova out of screenshots, screen recordings and the recent-apps preview. */
    fun onBlockScreenshotsChange(enabled: Boolean) = save { settingsRepository.setBlockScreenshots(enabled) }

    fun onBackUpNow() {
        backupRepository.backUpNow()
        mutableState.update { it.copy(notice = MeNotice.BACKUP_STARTED) }
    }

    fun onNoticeShown() {
        mutableState.update { it.copy(notice = null) }
    }

    private fun save(change: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                change()
                mutableState.update { it.copy(saveFailed = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ScanovaLog.warn(TAG, "setting_save_failed", e)
                mutableState.update { it.copy(saveFailed = true) }
            }
        }
    }

    private companion object {
        const val TAG = "Me"
    }
}
