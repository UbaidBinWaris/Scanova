package app.scanova.android.update

import androidx.lifecycle.ViewModel
import app.scanova.android.BuildConfig
import androidx.lifecycle.viewModelScope
import app.scanova.core.data.update.AppRelease
import app.scanova.core.data.update.AppUpdater
import app.scanova.core.data.update.UpdateFailure
import app.scanova.core.data.update.UpdateResult
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateUiState {
    data object Hidden : UpdateUiState

    /** A check the user asked for is running. */
    data object Checking : UpdateUiState

    /** Answer to a check the user asked for. */
    data class UpToDate(val versionName: String) : UpdateUiState

    data class Available(val release: AppRelease) : UpdateUiState

    /** Android must allow Scanova to install apps first (asked once per app). */
    data class NeedsPermission(val release: AppRelease) : UpdateUiState

    data class Downloading(val release: AppRelease, val progress: Float) : UpdateUiState

    /** Verified and ready: the screen hands it to Android's installer. */
    data class ReadyToInstall(val release: AppRelease, val apk: File) : UpdateUiState

    data class Failed(val release: AppRelease?, val reason: UpdateFailure) : UpdateUiState
}

/**
 * Drives the update dialog shown over the signed-in app: an automatic check at
 * most every few hours, and checks requested from Settings.
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updater: AppUpdater,
) : ViewModel() {

    private val mutableState = MutableStateFlow<UpdateUiState>(UpdateUiState.Hidden)
    val state: StateFlow<UpdateUiState> = mutableState.asStateFlow()
    private var download: Job? = null

    init {
        viewModelScope.launch { runCatching { updater.cleanUp() } }
        // Debug builds (version 1) would always be offered the published build; skip the
        // automatic check there. "Check for updates" in Settings still works.
        if (!BuildConfig.DEBUG && updater.shouldAutoCheck()) check(manual = false)
        viewModelScope.launch { updater.manualCheckRequests.collect { check(manual = true) } }
    }

    private fun check(manual: Boolean) {
        if (download?.isActive == true) return
        if (manual) mutableState.value = UpdateUiState.Checking
        viewModelScope.launch {
            mutableState.value = when (val result = updater.check()) {
                is UpdateResult.Success -> {
                    val release = result.value
                    when {
                        release == null -> if (manual) UpdateUiState.UpToDate(updater.currentVersionName) else UpdateUiState.Hidden
                        !manual && updater.wasSkipped(release) -> UpdateUiState.Hidden
                        else -> UpdateUiState.Available(release)
                    }
                }
                // An automatic check stays quiet when offline; a requested one explains.
                is UpdateResult.Failure -> if (manual) UpdateUiState.Failed(null, result.reason) else UpdateUiState.Hidden
            }
        }
    }

    fun onUpdate(release: AppRelease) {
        if (updater.canInstall()) startDownload(release) else mutableState.value = UpdateUiState.NeedsPermission(release)
    }

    /** Back from Android's "Install unknown apps" setting. */
    fun onPermissionScreenClosed(release: AppRelease) {
        if (updater.canInstall()) startDownload(release)
    }

    private fun startDownload(release: AppRelease) {
        download?.cancel()
        mutableState.value = UpdateUiState.Downloading(release, 0f)
        download = viewModelScope.launch {
            var shown = 0f
            val result = updater.download(release) { progress ->
                // About a hundred updates per download, not one per buffer.
                if (progress - shown >= 0.01f || progress >= 1f) {
                    shown = progress
                    mutableState.value = UpdateUiState.Downloading(release, progress)
                }
            }
            mutableState.value = when (result) {
                is UpdateResult.Success -> updater.installProblem(result.value, release)
                    ?.let { UpdateUiState.Failed(release, it) }
                    ?: UpdateUiState.ReadyToInstall(release, result.value)
                is UpdateResult.Failure -> UpdateUiState.Failed(release, result.reason)
            }
        }
    }

    fun onCancelDownload() {
        download?.cancel()
        mutableState.value = UpdateUiState.Hidden
    }

    /** "Later": this version is not offered again automatically. Not possible for a required one. */
    fun onLater(release: AppRelease) {
        if (release.required) return
        updater.skip(release)
        mutableState.value = UpdateUiState.Hidden
    }

    /** The installer was opened (or could not be). Android takes over from here. */
    fun onInstallerLaunched() {
        mutableState.value = UpdateUiState.Hidden
    }

    fun onInstallerMissing(release: AppRelease) {
        mutableState.value = UpdateUiState.Failed(release, UpdateFailure.NOT_THIS_APP)
    }

    fun onDismiss() {
        val current = mutableState.value
        if (current is UpdateUiState.Available && current.release.required) return
        if (current is UpdateUiState.Downloading) return
        mutableState.value = UpdateUiState.Hidden
    }
}
