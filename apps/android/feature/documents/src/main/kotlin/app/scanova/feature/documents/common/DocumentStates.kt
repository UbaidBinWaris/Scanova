package app.scanova.feature.documents.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.scanova.core.designsystem.R as DesignR
import app.scanova.core.designsystem.component.IconTile
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.component.SecondaryButton
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.documents.R

/**
 * Nothing scanned yet. Scanning is the one primary action; importing photos
 * from the gallery is the quieter alternative.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmptyLibrary(
    signedIn: Boolean,
    onScan: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(icon = Icons.Outlined.DocumentScanner, size = EmptyTileSize)
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = stringResource(R.string.feature_documents_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = stringResource(
                if (signedIn) R.string.feature_documents_empty_body_backup else R.string.feature_documents_empty_body_local,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = ReadableWidth),
        )
        Spacer(Modifier.height(Spacing.xl))
        // Side by side when they fit, stacked on narrow screens or with large text.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            PrimaryButton(
                text = stringResource(R.string.feature_documents_scan_first),
                onClick = onScan,
                icon = Icons.Outlined.DocumentScanner,
            )
            SecondaryButton(
                text = stringResource(R.string.feature_documents_action_import_images),
                onClick = onImport,
                icon = Icons.Outlined.PhotoLibrary,
            )
        }
    }
}

/** A search that matched nothing. Sits high on the screen, above the keyboard. */
@Composable
internal fun NoResults(onClearSearch: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(icon = Icons.Outlined.SearchOff, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = NoResultsTileSize)
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = stringResource(R.string.feature_documents_no_results_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = stringResource(R.string.feature_documents_no_results_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = ReadableWidth),
        )
        Spacer(Modifier.height(Spacing.md))
        QuietButton(text = stringResource(R.string.feature_documents_search_clear), onClick = onClearSearch)
    }
}

/** A part of a screen that could not be read, with a way to try again. Smaller than a full-screen error. */
@Composable
internal fun InlineError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(icon = Icons.Outlined.ErrorOutline, tint = MaterialTheme.colorScheme.error, size = NoResultsTileSize)
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(max = ReadableWidth)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(Spacing.lg))
        SecondaryButton(text = stringResource(DesignR.string.core_designsystem_action_retry), onClick = onRetry)
    }
}

private val EmptyTileSize = 64.dp
private val NoResultsTileSize = 56.dp

/** Keeps centred text at a comfortable line length. */
internal val ReadableWidth = 360.dp
