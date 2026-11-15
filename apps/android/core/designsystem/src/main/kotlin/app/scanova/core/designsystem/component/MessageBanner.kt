package app.scanova.core.designsystem.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.scanova.core.designsystem.theme.Spacing

enum class BannerTone { Info, Error }

/**
 * Inline message inside a screen (form errors, hints). The icon differs per
 * tone so the meaning never depends on colour alone.
 */
@Composable
fun MessageBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.Error,
) {
    val container = when (tone) {
        BannerTone.Info -> MaterialTheme.colorScheme.secondaryContainer
        BannerTone.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (tone) {
        BannerTone.Info -> MaterialTheme.colorScheme.onSecondaryContainer
        BannerTone.Error -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (tone == BannerTone.Error) Icons.Outlined.ErrorOutline else Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
