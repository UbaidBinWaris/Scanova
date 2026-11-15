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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.PasswordField
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.message
import app.scanova.core.designsystem.theme.Spacing

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
    Scaffold(
        topBar = {
            ScanovaTopBar(title = stringResource(R.string.feature_auth_change_password_title), onBack = onBack)
        },
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
                Text(
                    text = stringResource(
                        if (forced) {
                            R.string.feature_auth_change_password_forced_intro
                        } else {
                            R.string.feature_auth_change_password_intro
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

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
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth(),
                )

                state.error?.let { MessageBanner(text = it.message()) }

                PrimaryButton(
                    text = stringResource(R.string.feature_auth_change_password),
                    onClick = onSubmit,
                    loading = state.isSubmitting,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (forced) {
                    QuietButton(
                        text = stringResource(R.string.feature_auth_sign_out),
                        onClick = onSignOut,
                        enabled = !state.isSubmitting,
                    )
                }
            }
        }
    }
}
