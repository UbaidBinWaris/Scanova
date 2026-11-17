package app.scanova.feature.settings.serverai

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.onFailure
import app.scanova.core.data.serverai.ServerAiAccess
import app.scanova.core.data.serverai.ServerAiGroup
import app.scanova.core.data.serverai.ServerAiRepository
import app.scanova.core.data.serverai.ServerAiState
import app.scanova.core.data.serverai.ServerAiStatus
import app.scanova.core.data.serverai.statusOf
import app.scanova.feature.settings.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The owner's "Page clean-up" switch on the Me tab. The server's text AI has no
 * switch in the app: it is not used (the server keeps `AI_PROVIDER=none`).
 */
internal data class ServerAiUiState(
    /**
     * The page clean-up switch. Null: not an owner, not known yet, or a server
     * without page clean-up, so nothing is shown.
     */
    val cleanup: ServerAiStatus?,
    /** A flip is on its way: the switch waits for its answer. */
    val switching: Boolean,
    /** The last flip the server refused. */
    val error: AppError?,
) {
    /**
     * The (needed, free) memory to name when [error] is "not enough memory".
     * Null for any other error, or when the server gave no numbers: the general
     * message is shown then.
     */
    fun memoryShortfall(): Pair<Long, Long>? {
        val code = (error as? AppError.Api)?.code
        if (code != ApiErrorCode.NOT_ENOUGH_MEMORY) return null
        val switch = cleanup ?: return null
        val needed = switch.neededBytes ?: return null
        val free = switch.availableBytes ?: return null
        return needed to free
    }
}

internal fun serverAiUiState(access: ServerAiAccess, switching: Boolean, error: AppError?): ServerAiUiState =
    ServerAiUiState(cleanup = access.statusOf(ServerAiGroup.IMAGE), switching = switching, error = error)

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
 * The server is set up for this switch: it is turned on in the server's
 * settings and has the model. Until then, when it turns itself off and how much
 * memory the server has say nothing useful, so they are not shown.
 */
internal val ServerAiStatus.isSetUp: Boolean
    get() = state != ServerAiState.DISABLED && state != ServerAiState.NOT_INSTALLED

/**
 * The switch can be used unless there is nothing to load. Turning off always
 * works, so an owner can free the memory whatever the state.
 */
internal fun ServerAiStatus.switchEnabled(switching: Boolean): Boolean = when {
    switching -> false
    on -> true
    else -> isSetUp && state != ServerAiState.UNLOADING
}

@HiltViewModel
internal class ServerAiViewModel @Inject constructor(
    private val serverAi: ServerAiRepository,
) : ViewModel() {

    private val switching = MutableStateFlow(false)
    private val error = MutableStateFlow<AppError?>(null)

    val state: StateFlow<ServerAiUiState> = combine(serverAi.access, switching, error, ::serverAiUiState)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            ServerAiUiState(cleanup = null, switching = false, error = null),
        )

    init {
        // Opening the Me tab shows the current state, not the one from the last check-in.
        viewModelScope.launch { serverAi.refresh() }
    }

    /** Turns page clean-up on or off; a second flip waits for the first answer. */
    fun onSwitch(on: Boolean) {
        if (switching.value) return
        switching.value = true
        error.value = null
        viewModelScope.launch {
            serverAi.setOn(on, ServerAiGroup.IMAGE).onFailure { error.value = it }
            switching.value = false
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
