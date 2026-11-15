package app.scanova.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.scanova.core.designsystem.component.ScanovaCard
import app.scanova.core.designsystem.component.SectionHeader
import app.scanova.core.designsystem.theme.Spacing

/** Widest the settings column grows on tablets and in landscape. */
internal val SettingsMaxWidth = 640.dp

/** The icon tile in a ListRow; dividers start after it so they line up with the text. */
private val RowTileSize = 36.dp
private val NoteIconSize = 20.dp

/** A titled group of rows on one hairline card. [title] is null for a lone group (sign out). */
@Composable
internal fun SettingsGroup(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) SectionHeader(text = title)
        ScanovaCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(0.dp),
            content = content,
        )
    }
}

/** Hairline between rows of a group, indented to the text like system settings lists. */
@Composable
internal fun RowDivider(indented: Boolean = true) {
    HorizontalDivider(
        modifier = if (indented) Modifier.padding(start = Spacing.lg + RowTileSize + Spacing.md) else Modifier,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
}

/** Trailing "opens another screen" mark; mirrors in right-to-left languages. */
@Composable
internal fun Chevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A quiet explanatory line inside a group, with an icon so it is not just grey text. */
@Composable
internal fun NoteRow(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(NoteIconSize),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A label over a value, read together by screen readers; the value can be selected and copied. */
@Composable
internal fun DetailRow(label: String, value: String, monospace: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (monospace) FontFamily.Monospace else null,
            )
        }
    }
}

/** Share of the quota in use, 0..1, or null when there is no quota to measure against. */
internal fun storageFraction(usedBytes: Long, quotaBytes: Long?): Float? {
    if (quotaBytes == null || quotaBytes <= 0L) return null
    return (usedBytes.coerceAtLeast(0L).toDouble() / quotaBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

/** Storage counts as nearly full from this share on; the meter says so in words, too. */
internal const val STORAGE_WARNING_FRACTION = 0.9f

/** The first character of a name (whole code point, so no broken surrogate), upper-cased. */
internal fun initialOf(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    return String(Character.toChars(trimmed.codePointAt(0))).uppercase()
}

/** A CA fingerprint in groups of four, so it can be compared with the server's printout by eye. */
internal fun groupedFingerprint(fingerprint: String): String = fingerprint.chunked(4).joinToString(" ")
