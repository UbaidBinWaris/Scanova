package app.scanova.feature.settings.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.auth.AuthRepository
import app.scanova.core.data.model.DeviceSession
import app.scanova.core.designsystem.R as DesignR
import app.scanova.core.designsystem.component.BadgeTone
import app.scanova.core.designsystem.component.EmptyState
import app.scanova.core.designsystem.component.ErrorState
import app.scanova.core.designsystem.component.ListRow
import app.scanova.core.designsystem.component.LoadingState
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.StatusBadge
import app.scanova.core.designsystem.component.message
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.settings.R
import app.scanova.feature.settings.RowDivider
import app.scanova.feature.settings.SettingsGroup
import app.scanova.feature.settings.SettingsMaxWidth
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

private val RowSpinnerSize = 20.dp

data class SessionsUiState(
    val isLoading: Boolean = true,
    val sessions: List<DeviceSession> = emptyList(),
    /** The list could not be loaded at all. */
    val loadError: AppError? = null,
    /** Signing a device out failed; the list is still shown. */
    val actionError: AppError? = null,
    val revokingId: String? = null,
    /** The device the user asked to sign out, waiting for confirmation. */
    val confirming: DeviceSession? = null,
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

    fun onRevokeRequested(session: DeviceSession) {
        if (session.isThisDevice) return
        mutableState.update { it.copy(confirming = session, actionError = null) }
    }

    fun onRevokeDismissed() {
        mutableState.update { it.copy(confirming = null) }
    }

    fun onRevokeConfirmed() {
        val session = mutableState.value.confirming ?: return
        mutableState.update { it.copy(confirming = null) }
        onRevoke(session.id)
    }

    private fun onRevoke(id: String) {
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
        when {
            state.isLoading -> LoadingState(modifier = Modifier.padding(padding))
            loadError != null -> ErrorState(
                message = loadError.message(),
                onRetry = viewModel::load,
                modifier = Modifier.padding(padding),
            )
            state.sessions.isEmpty() -> EmptyState(
                icon = Icons.Rounded.Devices,
                title = stringResource(R.string.feature_settings_sessions_empty_title),
                body = stringResource(R.string.feature_settings_sessions_empty_body),
                actionLabel = stringResource(DesignR.string.core_designsystem_action_retry),
                onAction = viewModel::load,
                modifier = Modifier.padding(padding),
            )
            else -> SessionsList(state = state, onRevoke = viewModel::onRevokeRequested, modifier = Modifier.padding(padding))
        }
    }

    state.confirming?.let { session ->
        val name = session.displayName()
        AlertDialog(
            onDismissRequest = viewModel::onRevokeDismissed,
            title = { Text(stringResource(R.string.feature_settings_sessions_revoke_title, name)) },
            text = { Text(stringResource(R.string.feature_settings_sessions_revoke_body)) },
            confirmButton = {
                QuietButton(
                    text = stringResource(R.string.feature_settings_sessions_revoke_confirm),
                    onClick = viewModel::onRevokeConfirmed,
                    destructive = true,
                )
            },
            dismissButton = {
                QuietButton(
                    text = stringResource(DesignR.string.core_designsystem_action_cancel),
                    onClick = viewModel::onRevokeDismissed,
                )
            },
        )
    }
}

@Composable
private fun SessionsList(
    state: SessionsUiState,
    onRevoke: (DeviceSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    val thisDevice = state.sessions.filter { it.isThisDevice }
    val others = state.sessions.filterNot { it.isThisDevice }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = SettingsMaxWidth)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            Text(
                text = stringResource(R.string.feature_settings_sessions_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.actionError?.let { MessageBanner(text = it.message()) }

            if (thisDevice.isNotEmpty()) {
                SettingsGroup(title = stringResource(R.string.feature_settings_sessions_section_this)) {
                    thisDevice.forEachIndexed { index, session ->
                        if (index > 0) RowDivider()
                        SessionRow(session = session, revoking = false, onRevoke = {})
                    }
                }
            }
            SettingsGroup(title = stringResource(R.string.feature_settings_sessions_section_others)) {
                if (others.isEmpty()) {
                    ListRow(
                        title = stringResource(R.string.feature_settings_sessions_none_other),
                        leadingIcon = Icons.Rounded.Devices,
                        leadingTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                others.forEachIndexed { index, session ->
                    if (index > 0) RowDivider()
                    SessionRow(
                        session = session,
                        revoking = state.revokingId == session.id,
                        enabled = state.revokingId == null,
                        onRevoke = { onRevoke(session) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: DeviceSession,
    revoking: Boolean,
    onRevoke: () -> Unit,
    enabled: Boolean = true,
) {
    val name = session.displayName()
    val lastUsed = session.lastUsedAt?.let { at ->
        stringResource(
            R.string.feature_settings_sessions_last_used,
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(at.atZone(ZoneId.systemDefault())),
        )
    }
    val model = session.model.takeIf { it.isNotBlank() && it != session.deviceName }
    ListRow(
        title = name,
        supporting = listOfNotNull(model, if (session.isThisDevice) null else lastUsed).joinToString("\n").ifEmpty { null },
        leadingIcon = if (session.isThisDevice) Icons.Rounded.Smartphone else Icons.Rounded.PhoneAndroid,
        leadingTint = if (session.isThisDevice) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        trailing = {
            when {
                // This phone is signed out from Settings, not from this list.
                session.isThisDevice -> StatusBadge(
                    label = stringResource(R.string.feature_settings_sessions_this_device),
                    icon = Icons.Rounded.Smartphone,
                    tone = BadgeTone.Positive,
                )
                revoking -> CircularProgressIndicator(modifier = Modifier.size(RowSpinnerSize), strokeWidth = 2.dp)
                else -> IconButton(onClick = onRevoke, enabled = enabled) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Logout,
                        contentDescription = stringResource(R.string.feature_settings_sessions_sign_out_device, name),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

@Composable
private fun DeviceSession.displayName(): String =
    deviceName.ifBlank { stringResource(R.string.feature_settings_sessions_unknown_device) }
