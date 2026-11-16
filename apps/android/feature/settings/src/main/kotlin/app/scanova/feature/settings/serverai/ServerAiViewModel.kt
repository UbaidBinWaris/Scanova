package app.scanova.feature.settings.serverai

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.onFailure
import app.scanova.core.data.serverai.ServerAiAccess
import app.scanova.core.data.serverai.ServerAiRepository
import app.scanova.core.data.serverai.ServerAiState
import app.scanova.core.data.serverai.ServerAiStatus
import app.scanova.feature.settings.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The owner's AI switch on the Me tab. Shown only when the server says this account is an owner. */
internal data class ServerAiUiState(
    /** Null: not an owner (or not known yet), so nothing is shown. */
    val status: ServerAiStatus?,
    /** A switch request is on its way: the switch waits for the answer. */
    val switching: Boolean,
    val error: AppError?,
)

/** What the status line says, and which kind of icon goes with it (never colour alone). */
internal enum class StatusTone { READY, BUSY, OFF, PROBLEM }

internal data class StatusLine(@StringRes val text: Int, val tone: StatusTone)

internal fun ServerAiStatus.line(): StatusLine = when (state) {
    ServerAiState.READY -> StatusLine(R.string.feature_settings_server_ai_ready, StatusTone.READY)
    ServerAiState.LOADING -> StatusLine(R.string.feature_settings_server_ai_loading, StatusTone.BUSY)
    ServerAiState.UNLOADING -> StatusLine(R.string.feature_settings_server_ai_unloading, StatusTone.BUSY)
    ServerAiState.OFF -> StatusLine(R.string.feature_settings_server_ai_off, StatusTone.OFF)
    ServerAiState.FAILED -> StatusLine(R.string.feature_settings_server_ai_failed, StatusTone.PROBLEM)
    ServerAiState.NOT_INSTALLED -> StatusLine(R.string.feature_settings_server_ai_not_installed, StatusTone.PROBLEM)
    ServerAiState.UNAVAILABLE -> StatusLine(R.string.feature_settings_server_ai_unavailable, StatusTone.PROBLEM)
    ServerAiState.DISABLED -> StatusLine(R.string.feature_settings_server_ai_disabled, StatusTone.OFF)
    ServerAiState.UNKNOWN -> StatusLine(R.string.feature_settings_server_ai_unknown, StatusTone.PROBLEM)
}

/**
 * The switch can be used unless there is nothing to load. Turning off always
 * works, so an owner can free the memory whatever the state.
 */
internal fun ServerAiStatus.switchEnabled(switching: Boolean): Boolean = when {
    switching -> false
    on -> true
    else -> state != ServerAiState.DISABLED && state != ServerAiState.NOT_INSTALLED && state != ServerAiState.UNLOADING
}

@HiltViewModel
internal class ServerAiViewModel @Inject constructor(
    private val serverAi: ServerAiRepository,
) : ViewModel() {

    private val switching = MutableStateFlow(false)
    private val error = MutableStateFlow<AppError?>(null)

    val state: StateFlow<ServerAiUiState> = combine(serverAi.access, switching, error) { access, busy, failure ->
        ServerAiUiState(status = (access as? ServerAiAccess.Owner)?.status, switching = busy, error = failure)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ServerAiUiState(null, false, null))

    init {
        // Opening the Me tab shows the current state, not the one from the last check-in.
        viewModelScope.launch { serverAi.refresh() }
    }

    fun onSwitch(on: Boolean) {
        if (switching.value) return
        switching.value = true
        error.value = null
        viewModelScope.launch {
            serverAi.setOn(on).onFailure { error.value = it }
            switching.value = false
        }
    }

    fun onErrorShown() {
        error.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
