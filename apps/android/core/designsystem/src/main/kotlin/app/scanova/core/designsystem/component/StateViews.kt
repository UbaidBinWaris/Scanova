package app.scanova.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.scanova.core.designsystem.R
import app.scanova.core.designsystem.theme.Spacing

/** Full-area "working on it" placeholder. Announced to screen readers. */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    message: String = stringResource(R.string.core_designsystem_loading),
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.xl)
            .semantics(mergeDescendants = true) {
                contentDescription = message
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Shown when there is nothing to list. An empty screen always offers the next
 * step, so [actionLabel] and [onAction] are required together.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StateIcon(
            icon = icon,
            container = MaterialTheme.colorScheme.primaryContainer,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.xl))
            PrimaryButton(text = actionLabel, onClick = onAction, icon = actionIcon)
        }
    }
}

/** Full-area failure message with an optional way to try again. */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    retryLabel: String = stringResource(R.string.core_designsystem_action_retry),
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StateIcon(
            icon = Icons.Rounded.Error,
            container = MaterialTheme.colorScheme.errorContainer,
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(max = 360.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (onRetry != null) {
            Spacer(Modifier.height(Spacing.xl))
            SecondaryButton(text = retryLabel, onClick = onRetry)
        }
    }
}

/** The icon of an empty or error state, on a soft round tile so it reads as an illustration, not a button. */
@Composable
private fun StateIcon(icon: ImageVector, container: Color, tint: Color) {
    Surface(modifier = Modifier.size(StateIconTileSize), shape = CircleShape, color = container) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(StateIconSize))
        }
    }
}

private val StateIconTileSize = 80.dp
private val StateIconSize = 36.dp
