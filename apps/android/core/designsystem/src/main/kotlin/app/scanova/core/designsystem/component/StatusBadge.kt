package app.scanova.core.designsystem.component

import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.scanova.core.designsystem.theme.StatusColors

enum class BadgeTone { Neutral, Positive, Caution, Critical }

/**
 * Small status pill: an icon plus a word. The label carries the meaning; the
 * colour only reinforces it.
 */
@Composable
fun StatusBadge(
    label: String,
    icon: ImageVector,
    tone: BadgeTone,
    modifier: Modifier = Modifier,
) {
    // The active theme, not the system setting: a screen can force dark (the camera).
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val content: Color = when (tone) {
        BadgeTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        BadgeTone.Positive -> if (dark) StatusColors.positiveDark else StatusColors.positiveLight
        BadgeTone.Caution -> if (dark) StatusColors.cautionDark else StatusColors.cautionLight
        BadgeTone.Critical -> MaterialTheme.colorScheme.error
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = content,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
