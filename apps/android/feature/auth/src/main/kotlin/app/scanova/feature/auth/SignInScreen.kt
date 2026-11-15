package app.scanova.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.designsystem.R as DesignR
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.PasswordField
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.component.ScanovaTextField
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.message
import app.scanova.core.designsystem.theme.Spacing

@Composable
fun SignInRoute(viewModel: SignInViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SignInScreen(
        state = state,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSignIn = viewModel::onSignIn,
        onChangeServer = viewModel::onChangeServerRequested,
    )
    if (state.confirmingServerChange) {
        AlertDialog(
            onDismissRequest = viewModel::onChangeServerDismissed,
            title = { Text(stringResource(R.string.feature_auth_change_server_title)) },
            text = { Text(stringResource(R.string.feature_auth_change_server_body)) },
            confirmButton = {
                QuietButton(
                    text = stringResource(R.string.feature_auth_change_server_confirm),
                    onClick = viewModel::onChangeServerConfirmed,
                    destructive = true,
                )
            },
            dismissButton = {
                QuietButton(
                    text = stringResource(DesignR.string.core_designsystem_action_cancel),
                    onClick = viewModel::onChangeServerDismissed,
                )
            },
        )
    }
}

@Composable
private fun SignInScreen(
    state: SignInUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onChangeServer: () -> Unit,
) {
    Scaffold(
        topBar = { ScanovaTopBar(title = stringResource(R.string.feature_auth_sign_in_title)) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                if (state.serverLabel.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.feature_auth_server_label, state.serverLabel),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ScanovaTextField(
                    value = state.email,
                    onValueChange = onEmailChange,
                    label = stringResource(R.string.feature_auth_email_label),
                    enabled = !state.isSigningIn,
                    errorText = if (state.emailMissing) stringResource(R.string.feature_auth_email_required) else null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                PasswordField(
                    value = state.password,
                    onValueChange = onPasswordChange,
                    label = stringResource(R.string.feature_auth_password_label),
                    enabled = !state.isSigningIn,
                    errorText = if (state.passwordMissing) {
                        stringResource(R.string.feature_auth_password_required)
                    } else {
                        null
                    },
                    keyboardActions = KeyboardActions(onDone = { onSignIn() }),
                    modifier = Modifier.fillMaxWidth(),
                )

                state.error?.let { MessageBanner(text = it.message()) }

                PrimaryButton(
                    text = stringResource(R.string.feature_auth_sign_in),
                    onClick = onSignIn,
                    loading = state.isSigningIn,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(R.string.feature_auth_members_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                QuietButton(
                    text = stringResource(R.string.feature_auth_change_server),
                    onClick = onChangeServer,
                    enabled = !state.isSigningIn,
                )
            }
        }
    }
}
