package app.scanova.feature.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.PasswordField
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.ScanovaTextField
import app.scanova.core.designsystem.component.ScanovaTopBar

@Composable
fun SignInRoute(
    onBack: () -> Unit,
    onCreateAccount: () -> Unit,
    onSignedIn: () -> Unit,
    viewModel: SignInViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.signedIn) { if (state.signedIn) onSignedIn() }
    SignInScreen(
        state = state,
        onBack = onBack,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSignIn = viewModel::onSignIn,
        onCreateAccount = onCreateAccount,
    )
}

@Composable
private fun SignInScreen(
    state: SignInUiState,
    onBack: () -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onCreateAccount: () -> Unit,
) {
    val focus = LocalFocusManager.current
    Scaffold(
        topBar = { ScanovaTopBar(title = stringResource(R.string.feature_auth_sign_in_title), onBack = onBack) },
    ) { padding ->
        FormBody(modifier = Modifier.padding(padding)) {
            Text(
                text = stringResource(R.string.feature_auth_sign_in_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ServerLine(authority = state.serverLabel)

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
                errorText = if (state.passwordMissing) stringResource(R.string.feature_auth_password_required) else null,
                keyboardActions = KeyboardActions(
                    onDone = {
                        focus.clearFocus()
                        onSignIn()
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            state.error?.let { MessageBanner(text = it.authMessage(state.isDefaultServer)) }

            PrimaryButton(
                text = stringResource(R.string.feature_auth_sign_in),
                onClick = {
                    focus.clearFocus()
                    onSignIn()
                },
                loading = state.isSigningIn,
                modifier = Modifier.fillMaxWidth(),
            )

            SwitchFormLink(
                prompt = stringResource(R.string.feature_auth_sign_in_no_account),
                action = stringResource(R.string.feature_auth_sign_in_create_account),
                onClick = onCreateAccount,
                enabled = !state.isSigningIn,
            )
        }
    }
}
