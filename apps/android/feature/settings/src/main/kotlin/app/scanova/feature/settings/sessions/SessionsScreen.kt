package app.scanova.feature.settings.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.auth.AuthRepository
import app.scanova.core.data.model.DeviceSession
import app.scanova.core.designsystem.component.EmptyState
import app.scanova.core.designsystem.component.ErrorState
import app.scanova.core.designsystem.component.LoadingState
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.message
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.settings.R
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionsUiState(
    val isLoading: Boolean = true,
    val sessions: List<DeviceSession> = emptyList(),
    /** The list could not be loaded at all. */
    val loadError: AppError? = null,
    /** Signing a device out failed; the list is still shown. */
    val actionError: AppError? = null,
    val revokingId: String? = null,
)

/** State holder for the list of devices signed in to the account. */
@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() {
        mutableState.update { it.copy(isLoading = true, loadError = null) }
        viewModelScope.launch {
            val result = authRepository.sessions()
            mutableState.update {
                when (result) {
                    is AppResult.Success -> it.copy(isLoading = false, sessions = result.value)
                    is AppResult.Failure -> it.copy(isLoading = false, loadError = result.error)
                }
            }
        }
    }

    fun onRevoke(id: String) {
        if (mutableState.value.revokingId != null) return
        mutableState.update { it.copy(revokingId = id, actionError = null) }
        viewModelScope.launch {
            val result = authRepository.revokeSession(id)
            mutableState.update { current ->
                when (result) {
                    is AppResult.Success -> current.copy(
                        revokingId = null,
                        sessions = current.sessions.filterNot { it.id == id },
                    )
                    is AppResult.Failure -> current.copy(revokingId = null, actionError = result.error)
                }
            }
        }
    }
}

@Composable
internal fun SessionsRoute(onBack: () -> Unit, viewModel: SessionsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { ScanovaTopBar(title = stringResource(R.string.feature_settings_sessions_title), onBack = onBack) },
    ) { padding ->
        val loadError = state.loadError
        val others = state.sessions.count { !it.isThisDevice }
        when {
            state.isLoading -> LoadingState(modifier = Modifier.padding(padding))
            loadError != null -> ErrorState(
                message = loadError.message(),
                onRetry = viewModel::load,
                modifier = Modifier.padding(padding),
            )
            state.sessions.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Devices,
                title = stringResource(R.string.feature_settings_sessions_empty_title),
                body = stringResource(R.string.feature_settings_sessions_empty_body),
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                state.actionError?.let { error -> item(key = "error") { MessageBanner(text = error.message()) } }
                items(state.sessions, key = { it.id }) { session ->
                    SessionCard(session, revoking = state.revokingId == session.id) { viewModel.onRevoke(session.id) }
                }
                if (others == 0) {
                    item(key = "only-this") {
                        Text(
                            text = stringResource(R.string.feature_settings_sessions_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(session: DeviceSession, revoking: Boolean, onRevoke: () -> Unit) {
    val name = session.deviceName.ifBlank { stringResource(R.string.feature_settings_sessions_unknown_device) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(text = name, style = MaterialTheme.typography.titleMedium)
                if (session.model.isNotBlank() && session.model != session.deviceName) {
                    Text(
                        text = session.model,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val detail = when {
                    session.isThisDevice -> stringResource(R.string.feature_settings_sessions_this_device)
                    session.lastUsedAt != null -> stringResource(
                        R.string.feature_settings_sessions_last_used,
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                            .format(session.lastUsedAt!!.atZone(ZoneId.systemDefault())),
                    )
                    else -> null
                }
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (session.isThisDevice) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            // This phone is signed out from the settings screen, not from this list.
            if (!session.isThisDevice) {
                IconButton(onClick = onRevoke, enabled = !revoking) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Logout,
                        contentDescription = stringResource(R.string.feature_settings_sessions_sign_out_device, name),
                    )
                }
            }
        }
    }
}
