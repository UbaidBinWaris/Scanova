package app.scanova.android.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.android.R
import app.scanova.core.data.update.AppRelease
import app.scanova.core.data.update.UpdateFailure
import java.io.File

/** Shown over the signed-in app; invisible unless there is something to say. */
@Composable
internal fun UpdateHost(viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        (viewModel.state.value as? UpdateUiState.NeedsPermission)?.let { viewModel.onPermissionScreenClosed(it.release) }
    }

    when (val current = state) {
        UpdateUiState.Hidden -> Unit
        UpdateUiState.Checking -> SimpleDialog(
            title = stringResource(R.string.update_checking),
            body = null,
            confirm = stringResource(R.string.update_ok),
            onConfirm = viewModel::onDismiss,
            onDismiss = viewModel::onDismiss,
        )
        is UpdateUiState.UpToDate -> SimpleDialog(
            title = stringResource(R.string.update_up_to_date_title),
            body = stringResource(R.string.update_up_to_date_body, current.versionName),
            confirm = stringResource(R.string.update_ok),
            onConfirm = viewModel::onDismiss,
            onDismiss = viewModel::onDismiss,
        )
        is UpdateUiState.Available -> AvailableDialog(
            release = current.release,
            onUpdate = { viewModel.onUpdate(current.release) },
            onLater = { viewModel.onLater(current.release) },
            onDismiss = viewModel::onDismiss,
        )
        is UpdateUiState.NeedsPermission -> AlertDialog(
            onDismissRequest = viewModel::onDismiss,
            title = { Text(stringResource(R.string.update_permission_title)) },
            text = { Text(stringResource(R.string.update_permission_body)) },
            confirmButton = {
                TextButton(onClick = { permissionLauncher.launch(installPermissionSettings(context)) }) {
                    Text(stringResource(R.string.update_permission_open))
                }
            },
            dismissButton = { TextButton(onClick = viewModel::onDismiss) { Text(stringResource(R.string.update_cancel)) } },
        )
        is UpdateUiState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update_downloading, current.release.versionName)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(progress = { current.progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.update_progress, (current.progress * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::onCancelDownload) { Text(stringResource(R.string.update_cancel)) } },
        )
        is UpdateUiState.ReadyToInstall -> LaunchedEffect(current.apk) {
            try {
                context.startActivity(installIntent(context, current.apk))
                viewModel.onInstallerLaunched()
            } catch (e: ActivityNotFoundException) {
                viewModel.onInstallerMissing(current.release)
            }
        }
        is UpdateUiState.Failed -> SimpleDialog(
            title = stringResource(R.string.update_failed_title),
            body = stringResource(failureText(current.reason)),
            confirm = stringResource(R.string.update_ok),
            onConfirm = viewModel::onDismiss,
            onDismiss = viewModel::onDismiss,
        )
    }
}

@Composable
private fun AvailableDialog(release: AppRelease, onUpdate: () -> Unit, onLater: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (release.required) R.string.update_required_title else R.string.update_available_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        if (release.required) R.string.update_required_body else R.string.update_available_body,
                        release.versionName,
                    ),
                )
                release.notes.take(MAX_NOTES).forEach { note ->
                    Text("• $note", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onUpdate) { Text(stringResource(R.string.update_now)) } },
        dismissButton = if (release.required) {
            null
        } else {
            { TextButton(onClick = onLater) { Text(stringResource(R.string.update_later)) } }
        },
    )
}

@Composable
private fun SimpleDialog(title: String, body: String?, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = body?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
    )
}

private fun failureText(reason: UpdateFailure): Int = when (reason) {
    UpdateFailure.NO_CONNECTION -> R.string.update_failed_connection
    UpdateFailure.SERVER_PROBLEM -> R.string.update_failed_server
    UpdateFailure.NOT_ENOUGH_SPACE -> R.string.update_failed_space
    UpdateFailure.DAMAGED_DOWNLOAD -> R.string.update_failed_damaged
    UpdateFailure.NOT_THIS_APP -> R.string.update_failed_not_app
    UpdateFailure.NOT_NEWER -> R.string.update_failed_not_newer
    UpdateFailure.SIGNED_DIFFERENTLY -> R.string.update_failed_signature
}

private fun installIntent(context: Context, apk: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}$UPDATES_AUTHORITY_SUFFIX", apk)
    return Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
}

private fun installPermissionSettings(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

private const val MAX_NOTES = 5
private const val UPDATES_AUTHORITY_SUFFIX = ".updates"

/** Hands downloaded updates (cache/updates/) to Android's installer, read-only, one file at a time. */
class UpdateFileProvider : FileProvider(R.xml.update_paths)
