package app.scanova.feature.settings

import android.text.format.Formatter
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.data.model.PairedServer
import app.scanova.core.data.model.User
import app.scanova.core.designsystem.R as DesignR
import app.scanova.core.designsystem.component.LoadingState
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.component.ScanovaTextField
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.SecondaryButton
import app.scanova.core.designsystem.component.message
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.settings.lock.DeviceAuthenticator

@Composable
internal fun SettingsRoute(
    onBack: () -> Unit,
    onChangePassword: () -> Unit,
    onShowSessions: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? FragmentActivity
    val lockAvailable = remember(activity) { activity != null && DeviceAuthenticator.isAvailable(activity) }
    val lockPromptTitle = stringResource(R.string.feature_settings_app_lock_confirm_title)

    Scaffold(
        topBar = { ScanovaTopBar(title = stringResource(R.string.feature_settings_title), onBack = onBack) },
    ) { padding ->
        val user = state.user
        if (state.isLoading || user == null) {
            LoadingState(modifier = Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            state.error?.let { MessageBanner(text = it.message()) }

            AccountSection(
                user = user,
                enabled = !state.isWorking,
                onEditName = { viewModel.onShowDialog(SettingsDialog.EDIT_NAME) },
                onChangePassword = onChangePassword,
                onShowSessions = onShowSessions,
            )

            Section(title = stringResource(R.string.feature_settings_section_security)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = state.appLockEnabled,
                            enabled = lockAvailable || state.appLockEnabled,
                            role = Role.Switch,
                            onValueChange = { enable ->
                                // Turning the lock on asks for the screen lock once, so nobody
                                // can lock the owner out of an unlocked phone they picked up.
                                if (enable && activity != null) {
                                    DeviceAuthenticator.authenticate(activity, lockPromptTitle) { success ->
                                        if (success) viewModel.onAppLockChanged(true)
                                    }
                                } else if (!enable) {
                                    viewModel.onAppLockChanged(false)
                                }
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.feature_settings_app_lock_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(
                                if (lockAvailable || state.appLockEnabled) {
                                    R.string.feature_settings_app_lock_body
                                } else {
                                    R.string.feature_settings_app_lock_unavailable
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // The row is the control; the switch only shows its state.
                    Switch(checked = state.appLockEnabled, onCheckedChange = null, modifier = Modifier.padding(start = Spacing.md))
                }
            }

            state.server?.let { ServerSection(it) }

            Section(title = stringResource(R.string.feature_settings_section_about)) {
                Detail(stringResource(R.string.feature_settings_version), viewModel.appVersion)
                QuietButton(
                    text = stringResource(R.string.feature_settings_check_updates),
                    onClick = viewModel::onCheckForUpdates,
                )
            }

            SecondaryButton(
                text = stringResource(R.string.feature_settings_sign_out),
                onClick = { viewModel.onShowDialog(SettingsDialog.SIGN_OUT) },
                enabled = !state.isWorking,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SettingsDialogs(state, user, viewModel)
    }
}


@Composable
private fun AccountSection(
    user: User,
    enabled: Boolean,
    onEditName: () -> Unit,
    onChangePassword: () -> Unit,
    onShowSessions: () -> Unit,
) {
    val context = LocalContext.current
    Section(title = stringResource(R.string.feature_settings_section_account)) {
        Detail(stringResource(R.string.feature_settings_name_label), user.name)
        Detail(stringResource(R.string.feature_settings_email_label), user.email)
        val used = Formatter.formatShortFileSize(context, user.storageUsedBytes)
        val quota = user.storageQuotaBytes
        Detail(
            label = stringResource(R.string.feature_settings_storage_label),
            value = if (quota == null) {
                used
            } else {
                stringResource(
                    R.string.feature_settings_storage_with_quota,
                    used,
                    Formatter.formatShortFileSize(context, quota),
                )
            },
        )
        QuietButton(stringResource(R.string.feature_settings_edit_name), onEditName, enabled = enabled)
        QuietButton(stringResource(R.string.feature_settings_change_password), onChangePassword, enabled = enabled)
        QuietButton(stringResource(R.string.feature_settings_sessions), onShowSessions, enabled = enabled)
    }
}

@Composable
private fun ServerSection(server: PairedServer) {
    Section(title = stringResource(R.string.feature_settings_section_server)) {
        val unknown = stringResource(R.string.feature_settings_value_unknown)
        Detail(stringResource(R.string.feature_settings_server_name_label), server.name ?: unknown)
        Detail(stringResource(R.string.feature_settings_server_address_label), server.url)
        Detail(stringResource(R.string.feature_settings_server_version_label), server.serverVersion ?: unknown)
        Detail(
            label = stringResource(R.string.feature_settings_server_fingerprint_label),
            // Grouped in fours so it can be compared with the server's printout by eye.
            value = server.caFingerprint?.chunked(4)?.joinToString(" ")
                ?: stringResource(R.string.feature_settings_server_public_certificate),
            monospace = server.caFingerprint != null,
        )
        Detail(
            stringResource(R.string.feature_settings_server_ocr_label),
            stringResource(if (server.serverOcr) R.string.feature_settings_value_on else R.string.feature_settings_value_off),
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

@Composable
private fun Detail(label: String, value: String, monospace: Boolean = false) {
    // Label and value are read together as one item by screen readers.
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}

@Composable
private fun SettingsDialogs(state: SettingsUiState, user: User, viewModel: SettingsViewModel) {
    val cancel = stringResource(DesignR.string.core_designsystem_action_cancel)
    when (state.dialog) {
        null -> Unit
        SettingsDialog.EDIT_NAME -> {
            var name by rememberSaveable { mutableStateOf(user.name) }
            AlertDialog(
                onDismissRequest = viewModel::onDismissDialog,
                title = { Text(stringResource(R.string.feature_settings_edit_name_title)) },
                text = {
                    ScanovaTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(R.string.feature_settings_name_label),
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
            title = { Text(stringResource(R.string.feature_settings_sign_out_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text(stringResource(R.string.feature_settings_sign_out_body))
                    if (state.pendingSyncCount > 0) {
                        MessageBanner(
                            text = pluralStringResource(
                                R.plurals.feature_settings_sign_out_pending_warning,
                                state.pendingSyncCount,
                                state.pendingSyncCount,
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
    }
}
