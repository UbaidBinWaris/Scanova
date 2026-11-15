package app.scanova.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.NoAccounts
import androidx.compose.material.icons.outlined.PhoneAndroid
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
import app.scanova.core.designsystem.component.EmptyState
import app.scanova.core.designsystem.component.ErrorState
import app.scanova.core.designsystem.component.LoadingState
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.PasswordField
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.ScanovaCard
import app.scanova.core.designsystem.component.ScanovaTextField
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.theme.Spacing

/** Create an account (open sign-up). Closes itself once signed in. */
@Composable
fun SignUpRoute(
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onSignedUp: () -> Unit,
    viewModel: SignUpViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.signedUp) { if (state.signedUp) onSignedUp() }
    Scaffold(
        topBar = { ScanovaTopBar(title = stringResource(R.string.feature_auth_signup_title), onBack = onBack) },
    ) { padding ->
        val content = Modifier.padding(padding)
        when (state.availability) {
            SignUpAvailability.CHECKING -> LoadingState(
                message = stringResource(R.string.feature_auth_signup_checking),
                modifier = content,
            )
            SignUpAvailability.UNREACHABLE -> ErrorState(
                message = state.checkError?.authMessage(state.isDefaultServer)
                    ?: stringResource(R.string.feature_auth_error_offline),
                onRetry = viewModel::checkAvailability,
                modifier = content,
            )
            SignUpAvailability.CLOSED -> EmptyState(
                icon = Icons.Outlined.NoAccounts,
                title = stringResource(R.string.feature_auth_signup_closed_title),
                body = stringResource(R.string.feature_auth_signup_closed),
                actionLabel = stringResource(R.string.feature_auth_sign_in),
                onAction = onSignIn,
                modifier = content,
            )
            SignUpAvailability.OPEN -> SignUpForm(
                state = state,
                viewModel = viewModel,
                onSignIn = onSignIn,
                modifier = content,
            )
        }
    }
}

@Composable
private fun SignUpForm(
    state: SignUpUiState,
    viewModel: SignUpViewModel,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    val submit = {
        focus.clearFocus()
        viewModel.onCreateAccount()
    }
    FormBody(modifier = modifier) {
        Text(
            text = stringResource(R.string.feature_auth_signup_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ScanovaCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                BenefitRow(
                    icon = Icons.Outlined.CloudDone,
                    title = stringResource(R.string.feature_auth_signup_benefit_storage_title),
                    body = stringResource(R.string.feature_auth_signup_benefit_storage_body),
                )
                BenefitRow(
                    icon = Icons.Outlined.PhoneAndroid,
                    title = stringResource(R.string.feature_auth_signup_benefit_phone_title),
                    body = stringResource(R.string.feature_auth_signup_benefit_phone_body),
                )
            }
        }
        ServerLine(authority = state.serverLabel)

        ScanovaTextField(
            value = state.name,
            onValueChange = viewModel::onNameChange,
            label = stringResource(R.string.feature_auth_signup_name),
            enabled = !state.isCreating,
            errorText = if (state.nameMissing) stringResource(R.string.feature_auth_signup_name_missing) else null,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        ScanovaTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = stringResource(R.string.feature_auth_email_label),
            enabled = !state.isCreating,
            errorText = when {
                state.emailInvalid -> stringResource(R.string.feature_auth_signup_email_invalid)
                state.emailTaken -> stringResource(R.string.feature_auth_signup_email_taken)
                else -> null
            },
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
            onValueChange = viewModel::onPasswordChange,
            label = stringResource(R.string.feature_auth_password_label),
            enabled = !state.isCreating,
            supportingText = stringResource(R.string.feature_auth_new_password_hint),
            errorText = if (state.passwordTooShort) stringResource(R.string.feature_auth_new_password_too_short) else null,
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )

        state.error?.let { MessageBanner(text = it.authMessage(state.isDefaultServer)) }

        PrimaryButton(
            text = stringResource(R.string.feature_auth_signup_create),
            onClick = submit,
            loading = state.isCreating,
            modifier = Modifier.fillMaxWidth(),
        )
        SwitchFormLink(
            prompt = stringResource(R.string.feature_auth_signup_have_account),
            action = stringResource(R.string.feature_auth_sign_in),
            onClick = onSignIn,
            enabled = !state.isCreating,
        )
    }
}
