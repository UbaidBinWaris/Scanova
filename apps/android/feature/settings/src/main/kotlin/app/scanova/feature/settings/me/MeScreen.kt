package app.scanova.feature.settings.me

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.common.result.AppError
import app.scanova.core.data.settings.ThemeMode
import app.scanova.core.designsystem.component.ListRow
import app.scanova.core.designsystem.component.LoadingState
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.ScanovaCard
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.SecondaryButton
import app.scanova.core.designsystem.component.SectionHeader
import app.scanova.core.designsystem.component.message
import app.scanova.core.designsystem.component.raisedCardColor
import app.scanova.core.designsystem.theme.Accent
import app.scanova.core.designsystem.theme.IconSizes
import app.scanova.core.designsystem.theme.MinTouchTarget
import app.scanova.core.designsystem.theme.ScanovaThemeInfo
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.settings.Chevron
import app.scanova.feature.settings.R
import app.scanova.feature.settings.RowDivider
import app.scanova.feature.settings.SettingsGroup
import app.scanova.feature.settings.SettingsMaxWidth
import app.scanova.feature.settings.ToggleRow
import app.scanova.feature.settings.lock.DeviceAuthenticator
import app.scanova.feature.settings.serverai.ServerAiGroup
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * The Me tab: who is signed in and how much space they use, the backup, how
 * the app looks, how scanning starts, the app lock and screenshot blocking,
 * and the way into Settings.
 */
@Composable
internal fun MeRoute(
    onOpenSettings: () -> Unit,
    onSignIn: () -> Unit,
    onCreateAccount: () -> Unit,
    viewModel: MeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val backupStarted = stringResource(R.string.feature_settings_backup_started)
    LaunchedEffect(state.notice) {
        when (state.notice) {
            MeNotice.BACKUP_STARTED -> {
                viewModel.onNoticeShown()
                snackbar.showSnackbar(backupStarted)
            }
            null -> Unit
        }
    }

    Scaffold(
        topBar = { ScanovaTopBar(title = stringResource(R.string.feature_settings_me_title)) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.isLoading) {
            DelayedLoading(Modifier.padding(padding))
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
                if (state.saveFailed) MessageBanner(text = AppError.LocalStorage.message())

                when (val account = state.account) {
                    is MeAccount.SignedOut -> SignedOutCard(
                        localOnlyCount = account.localOnlyCount,
                        onCreateAccount = onCreateAccount,
                        onSignIn = onSignIn,
                    )
                    is MeAccount.SignedIn -> AccountCard(account)
                }
                if (state.backup != MeBackup.Off) {
                    BackupCard(backup = state.backup, onBackUpNow = viewModel::onBackUpNow)
                }
                // The server owner's AI switch; nothing for other accounts.
                if (state.signedIn) ServerAiGroup()
                AppearanceGroup(mode = state.themeMode, onModeChange = viewModel::onThemeModeChange)
                ScanningGroup(
                    autoCapture = state.autoCaptureEnabled,
                    autoEnhance = state.autoEnhanceEnabled,
                    onAutoCaptureChange = viewModel::onAutoCaptureChange,
                    onAutoEnhanceChange = viewModel::onAutoEnhanceChange,
                )
                SecurityGroup(
                    appLockEnabled = state.appLockEnabled,
                    screenshotsBlocked = state.screenshotsBlocked,
                    screenshotSettingLocked = state.screenshotSettingLockedByAppLock,
                    onAppLockChange = viewModel::onAppLockChange,
                    onBlockScreenshotsChange = viewModel::onBlockScreenshotsChange,
                )
                SettingsGroup(title = null) {
                    ListRow(
                        title = stringResource(R.string.feature_settings_title),
                        supporting = stringResource(
                            if (state.signedIn) R.string.feature_settings_open_body_signed_in else R.string.feature_settings_open_body,
                        ),
                        leadingIcon = Icons.Rounded.Settings,
                        leadingTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onOpenSettings,
                        trailing = { Chevron() },
                    )
                }
            }
        }
    }
}

/** Reading the account from this phone takes a moment only, so the spinner waits before it shows. */
@Composable
private fun DelayedLoading(modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LOADING_DELAY_MILLIS)
        visible = true
    }
    if (visible) LoadingState(modifier = modifier)
}

// ── Account ─────────────────────────────────────────────────────────────

/** Signed out: why an account is worth having, and the way to get one. The screen's one primary action. */
@Composable
private fun SignedOutCard(localOnlyCount: Int, onCreateAccount: () -> Unit, onSignIn: () -> Unit) {
    ScanovaCard(modifier = Modifier.fillMaxWidth(), containerColor = raisedCardColor()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            AccentTile(icon = Icons.Rounded.CloudUpload, accent = Accent.EMERALD)
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
        if (localOnlyCount > 0) {
            Row(
                modifier = Modifier
                    .padding(top = Spacing.md)
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSizes.small),
                )
                Text(
                    text = pluralStringResource(R.plurals.feature_settings_me_local_only, localOnlyCount, localOnlyCount),
                    style = MaterialTheme.typography.bodySmall,
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

/** Signed in: the person, and their storage against the quota. */
@Composable
private fun AccountCard(account: MeAccount.SignedIn) {
    ScanovaCard(modifier = Modifier.fillMaxWidth(), containerColor = raisedCardColor()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Avatar(initial = account.initial)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = account.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = account.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        StorageMeter(storage = account.storage, modifier = Modifier.padding(top = Spacing.lg))
    }
}

/** The person's initial in a tinted circle. Decorative: the name is right next to it. */
@Composable
private fun Avatar(initial: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .size(AvatarSize)
            .clearAndSetSemantics {},
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = initial, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun StorageMeter(storage: StorageUse, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val used = Formatter.formatShortFileSize(context, storage.usedBytes)
    val fraction = storage.fraction
    Column(
        modifier = modifier
            .fillMaxWidth()
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
                text = if (storage.quotaBytes == null) {
                    stringResource(R.string.feature_settings_storage_used, used)
                } else {
                    stringResource(
                        R.string.feature_settings_storage_with_quota,
                        used,
                        Formatter.formatShortFileSize(context, storage.quotaBytes),
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MeterHeight),
                color = if (storage.nearlyFull) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        if (storage.nearlyFull) {
            Text(
                text = stringResource(R.string.feature_settings_storage_nearly_full),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ── Backup ──────────────────────────────────────────────────────────────

@Composable
private fun BackupCard(backup: MeBackup, onBackUpNow: () -> Unit) {
    val upToDate = backup is MeBackup.UpToDate
    val lastBackupAt = when (backup) {
        is MeBackup.UpToDate -> backup.lastBackupAt
        is MeBackup.Waiting -> backup.lastBackupAt
        MeBackup.Off -> null
    }
    Column {
        SectionHeader(text = stringResource(R.string.feature_settings_section_backup))
        ScanovaCard(modifier = Modifier.fillMaxWidth(), containerColor = raisedCardColor()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) {},
            ) {
                AccentTile(
                    icon = if (upToDate) Icons.Rounded.CloudDone else Icons.Rounded.CloudUpload,
                    accent = if (upToDate) Accent.EMERALD else Accent.SLATE,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        text = when (backup) {
                            is MeBackup.Waiting -> pluralStringResource(
                                R.plurals.feature_settings_backup_waiting,
                                backup.count,
                                backup.count,
                            )
                            else -> stringResource(R.string.feature_settings_backup_up_to_date)
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = lastBackupText(lastBackupAt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SecondaryButton(
                text = stringResource(R.string.feature_settings_backup_now),
                onClick = onBackUpNow,
                icon = Icons.Rounded.Sync,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.lg),
            )
        }
    }
}

/** "Last backup 5 minutes ago", kept current while the tab is open. */
@Composable
private fun lastBackupText(at: Instant?): String {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(DateUtils.MINUTE_IN_MILLIS)
            value = System.currentTimeMillis()
        }
    }
    if (at == null) return stringResource(R.string.feature_settings_backup_never)
    val millis = at.toEpochMilli()
    if (now - millis < DateUtils.MINUTE_IN_MILLIS) return stringResource(R.string.feature_settings_backup_last_just_now)
    val relative = DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()
    return stringResource(R.string.feature_settings_backup_last, relative)
}

// ── Appearance ──────────────────────────────────────────────────────────

/** Light, dark, or the phone's choice. The whole app cross-fades to it. */
@Composable
private fun AppearanceGroup(mode: ThemeMode, onModeChange: (ThemeMode) -> Unit) {
    val options = listOf(
        Triple(ThemeMode.LIGHT, R.string.feature_settings_theme_light, Icons.Rounded.LightMode),
        Triple(ThemeMode.DARK, R.string.feature_settings_theme_dark, Icons.Rounded.DarkMode),
        Triple(ThemeMode.SYSTEM, R.string.feature_settings_theme_system, Icons.Rounded.BrightnessAuto),
    )
    SettingsGroup(title = stringResource(R.string.feature_settings_section_appearance)) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (option, label, icon) ->
                    SegmentedButton(
                        selected = mode == option,
                        onClick = { onModeChange(option) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        icon = {
                            SegmentedButtonDefaults.Icon(active = mode == option) {
                                Icon(icon, contentDescription = null, modifier = Modifier.size(SegmentedButtonDefaults.IconSize))
                            }
                        },
                        modifier = Modifier.heightIn(min = MinTouchTarget),
                    ) {
                        Text(text = stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Text(
                text = stringResource(R.string.feature_settings_theme_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Scanning ────────────────────────────────────────────────────────────

@Composable
private fun ScanningGroup(
    autoCapture: Boolean,
    autoEnhance: Boolean,
    onAutoCaptureChange: (Boolean) -> Unit,
    onAutoEnhanceChange: (Boolean) -> Unit,
) {
    val dark = ScanovaThemeInfo.isDark
    SettingsGroup(title = stringResource(R.string.feature_settings_section_scanning)) {
        ToggleRow(
            title = stringResource(R.string.feature_settings_auto_capture_title),
            supporting = stringResource(R.string.feature_settings_auto_capture_body),
            icon = Icons.Rounded.CenterFocusStrong,
            iconTint = Accent.EMERALD.content(dark),
            checked = autoCapture,
            onCheckedChange = onAutoCaptureChange,
        )
        RowDivider()
        ToggleRow(
            title = stringResource(R.string.feature_settings_auto_enhance_title),
            supporting = stringResource(R.string.feature_settings_auto_enhance_body),
            icon = Icons.Rounded.AutoFixHigh,
            iconTint = Accent.AMBER.content(dark),
            checked = autoEnhance,
            onCheckedChange = onAutoEnhanceChange,
        )
    }
}

// ── Security ────────────────────────────────────────────────────────────

@Composable
private fun SecurityGroup(
    appLockEnabled: Boolean,
    screenshotsBlocked: Boolean,
    screenshotSettingLocked: Boolean,
    onAppLockChange: (Boolean) -> Unit,
    onBlockScreenshotsChange: (Boolean) -> Unit,
) {
    val activity = LocalActivity.current as? FragmentActivity
    val lockAvailable = remember(activity) { activity != null && DeviceAuthenticator.isAvailable(activity) }
    val lockPromptTitle = stringResource(R.string.feature_settings_app_lock_confirm_title)
    val toggleEnabled = lockAvailable || appLockEnabled
    val tint = Accent.SLATE.content(ScanovaThemeInfo.isDark)
    SettingsGroup(title = stringResource(R.string.feature_settings_section_security)) {
        ToggleRow(
            title = stringResource(R.string.feature_settings_app_lock_title),
            supporting = stringResource(
                if (toggleEnabled) R.string.feature_settings_app_lock_body else R.string.feature_settings_app_lock_unavailable,
            ),
            icon = Icons.Rounded.Fingerprint,
            iconTint = tint,
            checked = appLockEnabled,
            enabled = toggleEnabled,
            onCheckedChange = { enable ->
                // Turning the lock on asks for the screen lock once, so nobody
                // can lock the owner out of an unlocked phone they picked up.
                if (enable && activity != null) {
                    DeviceAuthenticator.authenticate(activity, lockPromptTitle) { success ->
                        if (success) onAppLockChange(true)
                    }
                } else if (!enable) {
                    onAppLockChange(false)
                }
            },
        )
        RowDivider()
        // The app lock blocks screenshots too, so while it is on the switch shows
        // on, can't be changed, and says why.
        ToggleRow(
            title = stringResource(R.string.feature_settings_block_screenshots_title),
            supporting = stringResource(
                if (screenshotSettingLocked) {
                    R.string.feature_settings_block_screenshots_locked
                } else {
                    R.string.feature_settings_block_screenshots_body
                },
            ),
            icon = Icons.Rounded.Screenshot,
            iconTint = tint,
            checked = screenshotsBlocked,
            enabled = !screenshotSettingLocked,
            onCheckedChange = onBlockScreenshotsChange,
        )
    }
}

/** A colour-coded icon tile (accent container, accent icon), as on Home's quick actions. */
@Composable
private fun AccentTile(icon: ImageVector, accent: Accent) {
    val dark = ScanovaThemeInfo.isDark
    Surface(
        modifier = Modifier.size(TileSize),
        shape = MaterialTheme.shapes.medium,
        color = accent.container(dark),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, tint = accent.content(dark), modifier = Modifier.size(IconSizes.inRowTile))
        }
    }
}

private const val LOADING_DELAY_MILLIS = 150L
private val AvatarSize = 56.dp
private val TileSize = 40.dp
private val MeterHeight = 6.dp
