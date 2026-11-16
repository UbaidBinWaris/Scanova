package app.scanova.feature.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Password
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.PasswordField
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.component.ScanovaCard
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.message
import app.scanova.core.designsystem.component.raisedCardColor
import app.scanova.core.designsystem.theme.Accent

/**
 * Password change.
 *
 * @param forced the server demands the change (temporary password): there is no
 *   way back, only "Sign out". The app's root leaves this screen by itself once
 *   the account no longer requires a change.
 * @param onDone called after a voluntary change succeeded; null when [forced].
 */
@Composable
fun ChangePasswordRoute(
    forced: Boolean,
    onDone: (() -> Unit)?,
    viewModel: ChangePasswordViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.changed) {
        if (state.changed) onDone?.invoke()
    }
    ChangePasswordScreen(
        state = state,
        forced = forced,
        onBack = if (forced) null else onDone,
        onCurrentPasswordChange = viewModel::onCurrentPasswordChange,
        onNewPasswordChange = viewModel::onNewPasswordChange,
        onConfirmationChange = viewModel::onConfirmationChange,
        onSubmit = viewModel::onSubmit,
        onSignOut = viewModel::onSignOut,
    )
}

@Composable
private fun ChangePasswordScreen(
    state: ChangePasswordUiState,
    forced: Boolean,
    onBack: (() -> Unit)?,
    onCurrentPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSignOut: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val submit = {
        focus.clearFocus()
        onSubmit()
    }
    Scaffold(
        topBar = {
            ScanovaTopBar(title = stringResource(R.string.feature_auth_change_password_title), onBack = onBack)
        },
    ) { padding ->
        FormBody(modifier = Modifier.padding(padding)) {
            if (forced) {
                // A temporary password is the reason the app stopped here: say so up front.
                ScanovaCard(modifier = Modifier.fillMaxWidth(), containerColor = raisedCardColor()) {
                    BenefitRow(
                        icon = Icons.Rounded.Password,
                        title = stringResource(R.string.feature_auth_change_password_forced_title),
                        body = stringResource(R.string.feature_auth_change_password_forced_intro),
                        accent = Accent.AMBER,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.feature_auth_change_password_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            PasswordField(
                value = state.currentPassword,
                onValueChange = onCurrentPasswordChange,
                label = stringResource(R.string.feature_auth_current_password_label),
                enabled = !state.isSubmitting,
                errorText = when {
                    state.currentPasswordMissing -> stringResource(R.string.feature_auth_password_required)
                    state.currentPasswordWrong -> stringResource(R.string.feature_auth_current_password_wrong)
                    else -> null
                },
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth(),
            )

            PasswordField(
                value = state.newPassword,
                onValueChange = onNewPasswordChange,
                label = stringResource(R.string.feature_auth_new_password_label),
                enabled = !state.isSubmitting,
                errorText = when (state.newPasswordProblem) {
                    NewPasswordProblem.TOO_SHORT -> stringResource(R.string.feature_auth_new_password_too_short)
                    NewPasswordProblem.SAME_AS_CURRENT -> stringResource(R.string.feature_auth_new_password_same)
                    null -> null
                },
                supportingText = stringResource(R.string.feature_auth_new_password_hint),
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth(),
            )

            PasswordField(
                value = state.confirmation,
                onValueChange = onConfirmationChange,
                label = stringResource(R.string.feature_auth_confirm_password_label),
                enabled = !state.isSubmitting,
                errorText = if (state.confirmationMismatch) {
                    stringResource(R.string.feature_auth_confirm_password_mismatch)
                } else {
                    null
                },
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )

            state.error?.let { MessageBanner(text = it.message()) }

            PrimaryButton(
                text = stringResource(R.string.feature_auth_change_password),
                onClick = submit,
                loading = state.isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            )

            if (forced) {
                QuietButton(
                    text = stringResource(R.string.feature_auth_sign_out),
                    onClick = onSignOut,
                    enabled = !state.isSubmitting,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
