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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A switch flip the server refused, and which switch it was (for the memory numbers in the message). */
internal data class SwitchFailure(val group: ServerAiGroup, val error: AppError)

/** The owner's AI switches on the Me tab. Shown only when the server says this account is an owner. */
internal data class ServerAiUiState(
    /** The text AI switch. Null: not an owner (or not known yet), so nothing is shown. */
    val status: ServerAiStatus?,
    /** The page clean-up switch. Null: the server has none (or did not say), so that row is not shown. */
    val cleanup: ServerAiStatus?,
    /** Switches with a request on its way: each waits for its own answer, the other stays usable. */
    val switching: Set<ServerAiGroup>,
    val error: SwitchFailure?,
) {
    fun isSwitching(group: ServerAiGroup): Boolean = group in switching

    /**
     * The (needed, free) memory to name when [error] is "not enough memory", taken
     * from the switch that was flipped. Null for any other error, or when that
     * switch has no numbers: the general message is shown then.
     */
    fun memoryShortfall(): Pair<Long, Long>? {
        val failure = error ?: return null
        val code = (failure.error as? AppError.Api)?.code
        if (code != ApiErrorCode.NOT_ENOUGH_MEMORY) return null
        val switch = when (failure.group) {
            ServerAiGroup.TEXT -> status
            ServerAiGroup.IMAGE -> cleanup
        } ?: return null
        val needed = switch.neededBytes ?: return null
        val free = switch.availableBytes ?: return null
        return needed to free
    }
}

internal fun serverAiUiState(
    access: ServerAiAccess,
    switching: Set<ServerAiGroup>,
    error: SwitchFailure?,
): ServerAiUiState = ServerAiUiState(
    status = access.statusOf(ServerAiGroup.TEXT),
    cleanup = access.statusOf(ServerAiGroup.IMAGE),
    switching = switching,
    error = error,
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

    private val switching = MutableStateFlow<Set<ServerAiGroup>>(emptySet())
    private val error = MutableStateFlow<SwitchFailure?>(null)

    val state: StateFlow<ServerAiUiState> = combine(serverAi.access, switching, error, ::serverAiUiState)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            ServerAiUiState(status = null, cleanup = null, switching = emptySet(), error = null),
        )

    init {
        // Opening the Me tab shows the current state, not the one from the last check-in.
        viewModelScope.launch { serverAi.refresh() }
    }

    /** Flips the [group] switch; a second flip of the same switch waits for the first answer. */
    fun onSwitch(group: ServerAiGroup, on: Boolean) {
        if (group in switching.value) return
        switching.update { it + group }
        error.value = null
        viewModelScope.launch {
            serverAi.setOn(on, group).onFailure { error.value = SwitchFailure(group, it) }
            switching.update { it - group }
        }
    }

    fun onErrorShown() {
        error.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
