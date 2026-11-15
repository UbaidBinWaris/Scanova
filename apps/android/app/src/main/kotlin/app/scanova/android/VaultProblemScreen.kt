package app.scanova.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Shown when the phone's stored keys cannot be read. Never erases on its own:
 * "Try again" is the primary action, and erasing needs a second confirmation.
 */
@Composable
internal fun VaultProblemScreen(
    keysLost: Boolean,
    onRetry: () -> Unit,
    onReset: () -> Unit,
) {
    var confirmingReset by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(if (keysLost) R.string.vault_problem_title_lost else R.string.vault_problem_title_unavailable),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(if (keysLost) R.string.vault_problem_body_lost else R.string.vault_problem_body_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.widthIn(max = 480.dp),
        )
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.vault_problem_retry))
        }
        OutlinedButton(
            onClick = { confirmingReset = true },
            modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.vault_problem_reset), color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text(stringResource(R.string.vault_problem_reset_confirm_title)) },
            text = { Text(stringResource(R.string.vault_problem_reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingReset = false
                    onReset()
                }) {
                    Text(stringResource(R.string.vault_problem_reset_confirm_action), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReset = false }) { Text(stringResource(R.string.vault_problem_reset_cancel)) }
            },
        )
    }
}
