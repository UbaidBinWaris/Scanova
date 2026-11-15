package app.scanova.feature.settings

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
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.data.model.PairedServer
import app.scanova.core.data.model.User
import app.scanova.core.designsystem.R as DesignR
import app.scanova.core.designsystem.component.BadgeTone
import app.scanova.core.designsystem.component.ListRow
import app.scanova.core.designsystem.component.LoadingState
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.component.ScanovaTextField
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.StatusBadge
import app.scanova.core.designsystem.component.message
import app.scanova.core.designsystem.theme.Spacing

private val RowSpinnerSize = 20.dp

/**
 * Everything that is set once and rarely again: the account's details and
 * devices, the server, updates, and signing out. What people change often
 * (backup, appearance, scanning, app lock) lives on the Me tab.
 */
@Composable
internal fun SettingsRoute(
    onBack: () -> Unit,
    onChangePassword: () -> Unit,
    onShowSessions: () -> Unit,
    onChooseServer: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { ScanovaTopBar(title = stringResource(R.string.feature_settings_title), onBack = onBack) },
    ) { padding ->
        if (state.isLoading) {
            LoadingState(modifier = Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                state.error?.let { MessageBanner(text = it.message()) }

                state.user?.let { user ->
                    AccountGroup(
                        user = user,
                        enabled = !state.isWorking,
                        onEditName = { viewModel.onShowDialog(SettingsDialog.EDIT_NAME) },
                        onChangePassword = onChangePassword,
                        onShowSessions = onShowSessions,
                    )
                }
                state.server?.let { server ->
                    ServerGroup(
                        server = server,
                        state = state,
                        onChooseServer = onChooseServer,
                        onUseBuiltInServer = { viewModel.onShowDialog(SettingsDialog.USE_BUILT_IN_SERVER) },
                    )
                }
                AboutGroup(version = viewModel.appVersion, onCheckForUpdates = viewModel::onCheckForUpdates)

                if (state.user != null) {
                    SettingsGroup(title = null) {
                        ListRow(
                            title = stringResource(R.string.feature_settings_sign_out),
                            leadingIcon = Icons.AutoMirrored.Outlined.Logout,
                            destructive = true,
                            enabled = !state.isWorking,
                            onClick = { viewModel.onShowDialog(SettingsDialog.SIGN_OUT) },
                            trailing = if (state.isWorking) {
                                { CircularProgressIndicator(modifier = Modifier.size(RowSpinnerSize), strokeWidth = 2.dp) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }

    SettingsDialogs(state = state, viewModel = viewModel)
}

/** The account's details and devices. Who is signed in, and their storage, is on the Me tab. */
@Composable
private fun AccountGroup(
    user: User,
    enabled: Boolean,
    onEditName: () -> Unit,
    onChangePassword: () -> Unit,
    onShowSessions: () -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.feature_settings_section_account)) {
        ListRow(
            title = stringResource(R.string.feature_settings_edit_name),
            supporting = user.name.ifBlank { user.email },
            leadingIcon = Icons.Outlined.Badge,
            enabled = enabled,
            onClick = onEditName,
        )
        RowDivider()
        ListRow(
            title = stringResource(R.string.feature_settings_change_password),
            leadingIcon = Icons.Outlined.Password,
            enabled = enabled,
            onClick = onChangePassword,
            trailing = { Chevron() },
        )
        RowDivider()
        ListRow(
            title = stringResource(R.string.feature_settings_sessions),
            leadingIcon = Icons.Outlined.Devices,
            enabled = enabled,
            onClick = onShowSessions,
            trailing = { Chevron() },
        )
    }
}

@Composable
private fun ServerGroup(
    server: PairedServer,
    state: SettingsUiState,
    onChooseServer: () -> Unit,
    onUseBuiltInServer: () -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.feature_settings_section_server)) {
        // The address the app really talks to, never the name the server reports.
        ListRow(
            title = stringResource(
                if (server.isDefault) R.string.feature_settings_server_builtin_title else R.string.feature_settings_server_custom_title,
            ),
            supporting = serverAuthority(server.url),
            leadingIcon = Icons.Outlined.Dns,
            trailing = if (server.isDefault) {
                null
            } else {
                {
                    val pinned = server.caFingerprint != null
                    StatusBadge(
                        label = stringResource(
                            if (pinned) R.string.feature_settings_server_pinned_badge else R.string.feature_settings_server_public_badge,
                        ),
                        icon = if (pinned) Icons.Outlined.Lock else Icons.Outlined.Public,
                        tone = if (pinned) BadgeTone.Positive else BadgeTone.Neutral,
                    )
                }
            },
        )
        if (!server.isDefault) {
            server.serverVersion?.let { version ->
                RowDivider()
                DetailRow(label = stringResource(R.string.feature_settings_server_version_label), value = version)
            }
            RowDivider()
            DetailRow(
                label = stringResource(R.string.feature_settings_server_fingerprint_label),
                value = server.caFingerprint?.let(::groupedFingerprint)
                    ?: stringResource(R.string.feature_settings_server_public_certificate),
                monospace = server.caFingerprint != null,
            )
        }
        RowDivider(indented = false)
        if (state.canUseBuiltInServer) {
            ListRow(
                title = stringResource(R.string.feature_settings_server_use_builtin),
                supporting = stringResource(R.string.feature_settings_server_use_builtin_body),
                leadingIcon = Icons.Outlined.SettingsBackupRestore,
                onClick = onUseBuiltInServer,
            )
            RowDivider()
        }
        if (state.canChooseServer) {
            ListRow(
                title = stringResource(R.string.feature_settings_server_use_other),
                supporting = stringResource(R.string.feature_settings_server_use_other_body),
                leadingIcon = Icons.Outlined.SwapHoriz,
                onClick = onChooseServer,
                trailing = { Chevron() },
            )
        } else {
            NoteRow(icon = Icons.Outlined.Info, text = stringResource(R.string.feature_settings_server_locked))
        }
    }
}

@Composable
private fun AboutGroup(version: String, onCheckForUpdates: () -> Unit) {
    SettingsGroup(title = stringResource(R.string.feature_settings_section_about)) {
        ListRow(
            title = stringResource(R.string.feature_settings_version),
            leadingIcon = Icons.Outlined.Info,
            trailing = {
                Text(
                    text = version,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier = Modifier.semantics(mergeDescendants = true) {},
        )
        RowDivider()
        ListRow(
            title = stringResource(R.string.feature_settings_check_updates),
            leadingIcon = Icons.Outlined.SystemUpdate,
            onClick = onCheckForUpdates,
        )
    }
}

@Composable
private fun SettingsDialogs(state: SettingsUiState, viewModel: SettingsViewModel) {
    val cancel = stringResource(DesignR.string.core_designsystem_action_cancel)
    when (state.dialog) {
        null -> Unit
        SettingsDialog.EDIT_NAME -> {
            var name by rememberSaveable { mutableStateOf(state.user?.name.orEmpty()) }
            AlertDialog(
                onDismissRequest = viewModel::onDismissDialog,
                title = { Text(stringResource(R.string.feature_settings_edit_name_title)) },
                text = {
                    ScanovaTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(R.string.feature_settings_name_label),
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    QuietButton(
                        text = stringResource(DesignR.string.core_designsystem_action_save),
                        onClick = { viewModel.onNameChanged(name) },
                        enabled = name.isNotBlank(),
                    )
                },
                dismissButton = { QuietButton(text = cancel, onClick = viewModel::onDismissDialog) },
            )
        }
        SettingsDialog.SIGN_OUT -> AlertDialog(
            onDismissRequest = viewModel::onDismissDialog,
            icon = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
            title = { Text(stringResource(R.string.feature_settings_sign_out_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text(stringResource(R.string.feature_settings_sign_out_body))
                    if (state.pendingCount > 0) {
                        MessageBanner(
                            text = pluralStringResource(
                                R.plurals.feature_settings_sign_out_pending_warning,
                                state.pendingCount,
                                state.pendingCount,
                            ),
                        )
                    }
                }
            },
            confirmButton = {
                QuietButton(
                    text = stringResource(R.string.feature_settings_sign_out_confirm),
                    onClick = viewModel::onSignOutConfirmed,
                    destructive = true,
                )
            },
            dismissButton = { QuietButton(text = cancel, onClick = viewModel::onDismissDialog) },
        )
        SettingsDialog.USE_BUILT_IN_SERVER -> AlertDialog(
            onDismissRequest = viewModel::onDismissDialog,
            title = { Text(stringResource(R.string.feature_settings_server_use_builtin_title)) },
            text = { Text(stringResource(R.string.feature_settings_server_use_builtin_dialog)) },
            confirmButton = {
                QuietButton(
                    text = stringResource(R.string.feature_settings_server_use_builtin_confirm),
                    onClick = viewModel::onUseBuiltInServerConfirmed,
                )
            },
            dismissButton = { QuietButton(text = cancel, onClick = viewModel::onDismissDialog) },
        )
    }
}

/** `host` or `host:port` of a server URL; the raw text if it cannot be parsed. */
internal fun serverAuthority(serverUrl: String): String {
    val uri = runCatching { java.net.URI(serverUrl.trim()) }.getOrNull()
    val host = uri?.host ?: return serverUrl.trim()
    return if (uri.port == -1 || uri.port == 443) host else "$host:${uri.port}"
}
