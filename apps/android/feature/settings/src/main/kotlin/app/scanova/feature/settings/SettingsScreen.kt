package app.scanova.feature.settings

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.data.backup.BackupStatus
import app.scanova.core.data.model.PairedServer
import app.scanova.core.data.model.User
import app.scanova.core.designsystem.R as DesignR
import app.scanova.core.designsystem.component.BadgeTone
import app.scanova.core.designsystem.component.IconTile
import app.scanova.core.designsystem.component.ListRow
import app.scanova.core.designsystem.component.LoadingState
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.component.ScanovaCard
import app.scanova.core.designsystem.component.ScanovaTextField
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.SecondaryButton
import app.scanova.core.designsystem.component.StatusBadge
import app.scanova.core.designsystem.component.message
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.settings.lock.DeviceAuthenticator
import java.time.Instant

private val AvatarSize = 48.dp
private val RowSpinnerSize = 20.dp

@Composable
internal fun SettingsRoute(
    onBack: () -> Unit,
    onChangePassword: () -> Unit,
    onShowSessions: () -> Unit,
    onSignIn: () -> Unit,
    onCreateAccount: () -> Unit,
    onChooseServer: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val backupStarted = stringResource(R.string.feature_settings_backup_started)
    LaunchedEffect(state.notice) {
        when (state.notice) {
            SettingsNotice.BACKUP_STARTED -> {
                viewModel.onNoticeShown()
                snackbar.showSnackbar(backupStarted)
            }
            null -> Unit
        }
    }

    Scaffold(
        topBar = { ScanovaTopBar(title = stringResource(R.string.feature_settings_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
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

                val user = state.user
                if (user == null) {
                    SignedOutCard(onCreateAccount = onCreateAccount, onSignIn = onSignIn)
                } else {
                    AccountCard(
                        user = user,
                        enabled = !state.isWorking,
                        onEditName = { viewModel.onShowDialog(SettingsDialog.EDIT_NAME) },
                        onChangePassword = onChangePassword,
                        onShowSessions = onShowSessions,
                    )
                }

                BackupGroup(backup = state.backup, signedIn = state.signedIn, onBackUpNow = viewModel::onBackUpNow)
                SecurityGroup(appLockEnabled = state.appLockEnabled, onAppLockChanged = viewModel::onAppLockChanged)
                state.server?.let { server ->
                    ServerGroup(
                        server = server,
                        state = state,
                        onChooseServer = onChooseServer,
                        onUseBuiltInServer = { viewModel.onShowDialog(SettingsDialog.USE_BUILT_IN_SERVER) },
                    )
                }
                AboutGroup(version = viewModel.appVersion, onCheckForUpdates = viewModel::onCheckForUpdates)

                if (user != null) {
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

/** Signed out: why an account is worth having, and the way to get one. The screen's one primary action. */
@Composable
private fun SignedOutCard(onCreateAccount: () -> Unit, onSignIn: () -> Unit) {
    ScanovaCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            IconTile(icon = Icons.Outlined.CloudUpload)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = stringResource(R.string.feature_settings_account_cta_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.feature_settings_account_cta_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        PrimaryButton(
            text = stringResource(R.string.feature_settings_create_account),
            onClick = onCreateAccount,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.lg),
        )
        SecondaryButton(
            text = stringResource(R.string.feature_settings_sign_in),
            onClick = onSignIn,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.sm),
        )
    }
}

@Composable
private fun AccountCard(
    user: User,
    enabled: Boolean,
    onEditName: () -> Unit,
    onChangePassword: () -> Unit,
    onShowSessions: () -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.feature_settings_section_account)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Avatar(name = user.name, email = user.email)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = user.name.ifBlank { user.email },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = user.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        StorageMeter(usedBytes = user.storageUsedBytes, quotaBytes = user.storageQuotaBytes)
        RowDivider(indented = false)
        ListRow(
            title = stringResource(R.string.feature_settings_edit_name),
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

/** The person's initial in a tinted circle. Decorative: the name is right next to it. */
@Composable
private fun Avatar(name: String, email: String) {
    val initial = remember(name, email) { initialOf(name.ifBlank { email }) }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .size(AvatarSize)
            .clearAndSetSemantics {},
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = initial, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun StorageMeter(usedBytes: Long, quotaBytes: Long?) {
    val context = LocalContext.current
    val used = Formatter.formatShortFileSize(context, usedBytes)
    val fraction = storageFraction(usedBytes, quotaBytes)
    val nearlyFull = fraction != null && fraction >= STORAGE_WARNING_FRACTION
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.feature_settings_storage_title),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (quotaBytes == null) {
                    stringResource(R.string.feature_settings_storage_used, used)
                } else {
                    stringResource(
                        R.string.feature_settings_storage_with_quota,
                        used,
                        Formatter.formatShortFileSize(context, quotaBytes),
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = if (nearlyFull) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        if (nearlyFull) {
            Text(
                text = stringResource(R.string.feature_settings_storage_nearly_full),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun BackupGroup(backup: BackupStatus, signedIn: Boolean, onBackUpNow: () -> Unit) {
    SettingsGroup(title = stringResource(R.string.feature_settings_section_backup)) {
        if (!signedIn) {
            ListRow(
                title = stringResource(R.string.feature_settings_backup_off_title),
                supporting = if (backup.pendingCount > 0) {
                    pluralStringResource(
                        R.plurals.feature_settings_backup_off_body,
                        backup.pendingCount,
                        backup.pendingCount,
                    )
                } else {
                    stringResource(R.string.feature_settings_backup_off_body_empty)
                },
                leadingIcon = Icons.Outlined.CloudOff,
                leadingTint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SettingsGroup
        }
        val upToDate = backup.pendingCount == 0
        ListRow(
            title = if (upToDate) {
                stringResource(R.string.feature_settings_backup_up_to_date)
            } else {
                pluralStringResource(R.plurals.feature_settings_backup_waiting, backup.pendingCount, backup.pendingCount)
            },
            supporting = lastBackupText(backup.lastBackupAt),
            leadingIcon = if (upToDate) Icons.Outlined.CloudDone else Icons.Outlined.CloudUpload,
        )
        RowDivider()
        ListRow(
            title = stringResource(R.string.feature_settings_backup_now),
            supporting = stringResource(R.string.feature_settings_backup_now_body),
            leadingIcon = Icons.Outlined.Sync,
            onClick = onBackUpNow,
        )
    }
}

@Composable
private fun lastBackupText(at: Instant?): String {
    if (at == null) return stringResource(R.string.feature_settings_backup_never)
    val now = System.currentTimeMillis()
    val millis = at.toEpochMilli()
    if (now - millis < DateUtils.MINUTE_IN_MILLIS) return stringResource(R.string.feature_settings_backup_last_just_now)
    val relative = DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()
    return stringResource(R.string.feature_settings_backup_last, relative)
}

@Composable
private fun SecurityGroup(appLockEnabled: Boolean, onAppLockChanged: (Boolean) -> Unit) {
    val activity = LocalActivity.current as? FragmentActivity
    val lockAvailable = remember(activity) { activity != null && DeviceAuthenticator.isAvailable(activity) }
    val lockPromptTitle = stringResource(R.string.feature_settings_app_lock_confirm_title)
    val toggleEnabled = lockAvailable || appLockEnabled
    SettingsGroup(title = stringResource(R.string.feature_settings_section_security)) {
        ListRow(
            title = stringResource(R.string.feature_settings_app_lock_title),
            supporting = stringResource(
                if (toggleEnabled) R.string.feature_settings_app_lock_body else R.string.feature_settings_app_lock_unavailable,
            ),
            leadingIcon = Icons.Outlined.Fingerprint,
            enabled = toggleEnabled,
            // The row is the control; the switch only shows its state.
            modifier = Modifier.toggleable(
                value = appLockEnabled,
                enabled = toggleEnabled,
                role = Role.Switch,
                onValueChange = { enable ->
                    // Turning the lock on asks for the screen lock once, so nobody
                    // can lock the owner out of an unlocked phone they picked up.
                    if (enable && activity != null) {
                        DeviceAuthenticator.authenticate(activity, lockPromptTitle) { success ->
                            if (success) onAppLockChanged(true)
                        }
                    } else if (!enable) {
                        onAppLockChanged(false)
                    }
                },
            ),
            trailing = { Switch(checked = appLockEnabled, onCheckedChange = null, enabled = toggleEnabled) },
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
