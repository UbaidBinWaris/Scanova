package app.scanova.feature.pairing

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.designsystem.component.BadgeTone
import app.scanova.core.designsystem.component.BannerTone
import app.scanova.core.designsystem.component.ListRow
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.component.ScanovaCard
import app.scanova.core.designsystem.component.ScanovaTextField
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.SectionHeader
import app.scanova.core.designsystem.component.StatusBadge
import app.scanova.core.designsystem.component.message
import app.scanova.core.designsystem.theme.Spacing

/** Android 17: traffic to the local network needs this runtime permission. */
private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
private const val LOCAL_NETWORK_PERMISSION_SDK = 37

/**
 * Entry point of the pairing feature.
 *
 * @param initialPairingCode a `scanova://pair?…` link the app was opened with, if any.
 * @param onInitialPairingCodeConsumed called once that link has been handed to the screen.
 */
@Composable
fun PairingRoute(
    initialPairingCode: String?,
    onInitialPairingCodeConsumed: () -> Unit,
    onBack: () -> Unit,
    onPaired: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.paired) { if (state.paired) onPaired() }
    BackHandler(enabled = !state.scannerOpen, onBack = onBack)
    val context = LocalContext.current

    // The system asks once; whatever the answer, pairing goes ahead. If access
    // stays blocked, the connection error explains how to allow it.
    val localNetworkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onConnect()
    }
    val connect = {
        if (needsLocalNetworkPermission(context)) {
            localNetworkLauncher.launch(LOCAL_NETWORK_PERMISSION)
        } else {
            viewModel.onConnect()
        }
    }

    state.confirmation?.let { confirmation ->
        PairingConfirmationDialog(
            confirmation = confirmation,
            onConnect = {
                viewModel.onConfirmationAccepted()
                connect()
            },
            onDismiss = viewModel::onConfirmationDismissed,
        )
    }

    LaunchedEffect(initialPairingCode) {
        if (initialPairingCode != null) {
            viewModel.onPairingCode(initialPairingCode)
            onInitialPairingCodeConsumed()
        }
    }

    if (state.scannerOpen) {
        BackHandler(onBack = viewModel::onCloseScanner)
        PairingScannerScreen(
            codeRejected = state.scannedCodeRejected,
            onCode = viewModel::onPairingCode,
            onClose = viewModel::onCloseScanner,
        )
    } else {
        PairingFormScreen(
            state = state,
            onBack = onBack,
            onServerUrlChange = viewModel::onServerUrlChange,
            onFingerprintChange = viewModel::onFingerprintChange,
            onConnect = connect,
            onOpenScanner = viewModel::onOpenScanner,
        )
    }
}

private fun needsLocalNetworkPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= LOCAL_NETWORK_PERMISSION_SDK &&
        ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED

/** Widest the form grows on tablets and in landscape. */
private val FormMaxWidth = 480.dp

@Composable
private fun PairingFormScreen(
    state: PairingUiState,
    onBack: () -> Unit,
    onServerUrlChange: (String) -> Unit,
    onFingerprintChange: (String) -> Unit,
    onConnect: () -> Unit,
    onOpenScanner: () -> Unit,
) {
    Scaffold(
        topBar = { ScanovaTopBar(title = stringResource(R.string.feature_pairing_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = FormMaxWidth)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                Text(
                    text = stringResource(R.string.feature_pairing_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MessageBanner(text = stringResource(R.string.feature_pairing_note), tone = BannerTone.Info)

                ScanovaCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                    ListRow(
                        title = stringResource(R.string.feature_pairing_scan_code),
                        supporting = stringResource(R.string.feature_pairing_scan_code_body),
                        leadingIcon = Icons.Rounded.QrCodeScanner,
                        enabled = !state.isConnecting,
                        onClick = onOpenScanner,
                        trailing = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }

                // The header belongs to the fields below it, so it sits closer to them.
                Column(modifier = Modifier.padding(top = Spacing.sm)) {
                    SectionHeader(text = stringResource(R.string.feature_pairing_manual_header))
                    ScanovaTextField(
                        value = state.serverUrl,
                        onValueChange = onServerUrlChange,
                        label = stringResource(R.string.feature_pairing_url_label),
                        enabled = !state.isConnecting,
                        errorText = if (state.serverUrlInvalid) stringResource(R.string.feature_pairing_url_error) else null,
                        supportingText = stringResource(R.string.feature_pairing_url_hint),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                            autoCorrectEnabled = false,
                            capitalization = KeyboardCapitalization.None,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                ScanovaTextField(
                    value = state.fingerprint,
                    onValueChange = onFingerprintChange,
                    label = stringResource(R.string.feature_pairing_fingerprint_label),
                    enabled = !state.isConnecting,
                    errorText = if (state.fingerprintInvalid) {
                        stringResource(R.string.feature_pairing_fingerprint_error)
                    } else {
                        null
                    },
                    supportingText = stringResource(R.string.feature_pairing_fingerprint_hint),
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onConnect() }),
                    modifier = Modifier.fillMaxWidth(),
                )

                state.error?.let { error ->
                    MessageBanner(text = error.message())
                }
                if (state.isConnecting) {
                    MessageBanner(
                        text = stringResource(R.string.feature_pairing_connecting),
                        tone = BannerTone.Info,
                    )
                }

                PrimaryButton(
                    text = stringResource(R.string.feature_pairing_connect),
                    onClick = onConnect,
                    loading = state.isConnecting,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PairingScannerScreen(
    codeRejected: Boolean,
    onCode: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        permissionRequested = true
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            ScanovaTopBar(title = stringResource(R.string.feature_pairing_scanner_title), onBack = onClose)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            when {
                !hasPermission -> CameraPermissionExplanation(
                    // After a refusal Android may not show its dialog again; offer the settings page then.
                    offerSettings = permissionRequested,
                    onAllow = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.weight(1f),
                )
                cameraFailed -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    MessageBanner(text = stringResource(R.string.feature_pairing_camera_unavailable))
                }
                else -> {
                    Text(
                        text = stringResource(R.string.feature_pairing_scanner_instruction),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    val previewDescription = stringResource(R.string.feature_pairing_scanner_preview_description)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.scrim)
                            .semantics { contentDescription = previewDescription },
                    ) {
                        QrScannerPreview(
                            onCode = onCode,
                            onCameraError = { cameraFailed = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (codeRejected) {
                        MessageBanner(text = stringResource(R.string.feature_pairing_scanner_not_a_code))
                    }
                }
            }
            QuietButton(text = stringResource(R.string.feature_pairing_scanner_manual), onClick = onClose)
        }
    }
}

@Composable
private fun CameraPermissionExplanation(
    offerSettings: Boolean,
    onAllow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.feature_pairing_camera_permission_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = stringResource(R.string.feature_pairing_camera_permission_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xl))
        if (offerSettings) {
            PrimaryButton(
                text = stringResource(R.string.feature_pairing_camera_permission_settings),
                onClick = { context.openAppSettings() },
            )
        } else {
            PrimaryButton(
                text = stringResource(R.string.feature_pairing_camera_permission_allow),
                onClick = onAllow,
            )
        }
    }
}

private fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

/** Asks the user to confirm the server a pairing code points at, showing its exact address. */
@Composable
private fun PairingConfirmationDialog(
    confirmation: PairingConfirmation,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Dns, contentDescription = null) },
        title = { Text(stringResource(R.string.feature_pairing_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                // The exact host is the thing to check, so it stands apart from the prose.
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.feature_pairing_confirm_server, confirmation.authority),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(Spacing.md),
                    )
                }
                StatusBadge(
                    label = stringResource(
                        if (confirmation.pinned) {
                            R.string.feature_pairing_confirm_pinned_badge
                        } else {
                            R.string.feature_pairing_confirm_public_badge
                        },
                    ),
                    icon = if (confirmation.pinned) Icons.Rounded.Lock else Icons.Rounded.Public,
                    tone = if (confirmation.pinned) BadgeTone.Positive else BadgeTone.Caution,
                )
                Text(
                    stringResource(
                        if (confirmation.pinned) {
                            R.string.feature_pairing_confirm_pinned
                        } else {
                            R.string.feature_pairing_confirm_public
                        },
                    ),
                )
                Text(stringResource(R.string.feature_pairing_confirm_warning))
            }
        },
        confirmButton = {
            QuietButton(text = stringResource(R.string.feature_pairing_connect), onClick = onConnect)
        },
        dismissButton = {
            QuietButton(text = stringResource(R.string.feature_pairing_confirm_cancel), onClick = onDismiss)
        },
    )
}
