package app.scanova.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.scanova.core.common.result.AppError
import app.scanova.core.designsystem.component.IconTile
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.component.message
import app.scanova.core.designsystem.theme.Spacing

/** Widest a form may grow on tablets and in landscape, so lines stay readable. */
internal val FormMaxWidth = 480.dp

/** The icon tile size used inside lists and cards (matches ListRow). */
internal val RowTileSize = 36.dp

private val BrandMarkSize = 56.dp
private val ServerIconSize = 16.dp

/**
 * The scrolling, centred body every account form shares: start-aligned
 * content no wider than [FormMaxWidth], moved above the keyboard.
 */
@Composable
internal fun FormBody(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = FormMaxWidth)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            content = content,
        )
    }
}

/** Scanova's mark: a document between scan corners, on the accent colour. Decorative. */
@Composable
internal fun BrandMark(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(BrandMarkSize),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = ScanovaGlyph,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(BrandMarkSize * 0.6f),
            )
        }
    }
}

/**
 * Which server the form talks to. Always the address the app really uses,
 * never a name the server reports about itself (a look-alike could claim any
 * name).
 */
@Composable
internal fun ServerLine(authority: String, modifier: Modifier = Modifier) {
    if (authority.isEmpty()) return
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(Icons.Outlined.Dns, contentDescription = null, modifier = Modifier.size(ServerIconSize))
            Text(
                text = stringResource(R.string.feature_auth_server_label, authority),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** An icon tile with a short title and one supporting sentence, read as one item. */
@Composable
internal fun BenefitRow(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        IconTile(icon = icon, size = RowTileSize)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Question? Action" under a form, e.g. "New to Scanova? Create a free account". */
@Composable
internal fun SwitchFormLink(
    prompt: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = prompt,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        QuietButton(text = action, onClick = onClick, enabled = enabled)
    }
}

/**
 * The sentence for an error on the account screens. The built-in server is on
 * the internet, so "no connection" there means the phone is offline, not that
 * it is on the wrong Wi-Fi as the shared message assumes for home servers.
 */
@Composable
internal fun AppError.authMessage(isDefaultServer: Boolean): String = when {
    this == AppError.NoConnection && isDefaultServer -> stringResource(R.string.feature_auth_error_offline)
    else -> message()
}

/** 24×24 glyph: four scan corners around three lines of text. Tinted by its Icon. */
internal val ScanovaGlyph: ImageVector by lazy {
    val ink = SolidColor(Color.Black)
    ImageVector.Builder(
        name = "ScanovaGlyph",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = ink,
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(4f, 8.5f)
            verticalLineTo(6f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 6f, y1 = 4f)
            horizontalLineTo(8.5f)
            moveTo(15.5f, 4f)
            horizontalLineTo(18f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20f, y1 = 6f)
            verticalLineTo(8.5f)
            moveTo(20f, 15.5f)
            verticalLineTo(18f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 18f, y1 = 20f)
            horizontalLineTo(15.5f)
            moveTo(8.5f, 20f)
            horizontalLineTo(6f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 4f, y1 = 18f)
            verticalLineTo(15.5f)
        }
        path(stroke = ink, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
            moveTo(8.5f, 9.5f)
            horizontalLineTo(15.5f)
            moveTo(8.5f, 12f)
            horizontalLineTo(15.5f)
            moveTo(8.5f, 14.5f)
            horizontalLineTo(12.5f)
        }
    }.build()
}
