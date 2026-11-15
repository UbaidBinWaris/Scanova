package app.scanova.feature.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.EnhancedEncryption
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.theme.Accent
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.Spacing

/**
 * First launch only: what Scanova is, then straight into the app. No account
 * is needed, so the one primary action starts scanning; signing in is quiet.
 */
@Composable
fun WelcomeRoute(onGetStarted: () -> Unit, onSignIn: () -> Unit) {
    val entered = remember { MutableTransitionState(false).apply { targetState = true } }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            // The story scrolls on small screens and sits centred on tall ones.
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val viewport = maxHeight
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = viewport)
                            .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        AnimatedVisibility(
                            visibleState = entered,
                            enter = fadeIn(Motion.enter()) +
                                slideInVertically(Motion.enter()) { height -> height / 24 },
                        ) {
                            WelcomeStory(modifier = Modifier.widthIn(max = FormMaxWidth).fillMaxWidth())
                        }
                    }
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = FormMaxWidth)
                    .fillMaxWidth()
                    .padding(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                PrimaryButton(
                    text = stringResource(R.string.feature_auth_welcome_start),
                    onClick = onGetStarted,
                    modifier = Modifier.fillMaxWidth(),
                )
                QuietButton(
                    text = stringResource(R.string.feature_auth_welcome_sign_in),
                    onClick = onSignIn,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun WelcomeStory(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            BrandMark()
            Text(
                text = stringResource(R.string.feature_auth_brand_name),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Text(
            text = stringResource(R.string.feature_auth_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier
                .padding(top = Spacing.xxl)
                .semantics { heading() },
        )
        Text(
            text = stringResource(R.string.feature_auth_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.md),
        )
        Column(
            modifier = Modifier.padding(top = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            BenefitRow(
                icon = Icons.Outlined.DocumentScanner,
                title = stringResource(R.string.feature_auth_welcome_point_scan_title),
                body = stringResource(R.string.feature_auth_welcome_point_scan_body),
                accent = Accent.BLUE,
            )
            BenefitRow(
                icon = Icons.Outlined.EnhancedEncryption,
                title = stringResource(R.string.feature_auth_welcome_point_private_title),
                body = stringResource(R.string.feature_auth_welcome_point_private_body),
                accent = Accent.GREEN,
            )
            BenefitRow(
                icon = Icons.Outlined.CloudDone,
                title = stringResource(R.string.feature_auth_welcome_point_backup_title),
                body = stringResource(R.string.feature_auth_welcome_point_backup_body),
                accent = Accent.PURPLE,
            )
        }
    }
}
