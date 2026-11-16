package app.scanova.feature.scan.ui

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.scanova.core.designsystem.theme.MinTouchTarget
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.core.designsystem.theme.pressScale
import app.scanova.feature.scan.R

/** One page in a [PageThumbStrip]. */
internal data class StripPage(val id: String, val thumbnail: Bitmap?, val busy: Boolean)

/**
 * Every page as a small thumbnail, optionally followed by tiles to add pages:
 * with the camera ([onAddPage]) and from the gallery ([onAddPhotos]). The
 * selected page is framed; screen readers can move pages with custom actions.
 */
@Composable
internal fun PageThumbStrip(
    pages: List<StripPage>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onMove: (pageId: String, offset: Int) -> Unit,
    modifier: Modifier = Modifier,
    onAddPage: (() -> Unit)? = null,
    onAddPhotos: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    // Keep the selected page in sight when it changes from elsewhere.
    LaunchedEffect(selectedIndex, pages.size) {
        if (!listState.isFullyVisible(selectedIndex)) listState.animateScrollToItem((selectedIndex - 1).coerceAtLeast(0))
    }
    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        contentPadding = PaddingValues(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
            StripThumbnail(
                page = page,
                number = index + 1,
                total = pages.size,
                selected = index == selectedIndex,
                enabled = enabled,
                onClick = { onSelect(page.id) },
                onMoveEarlier = { onMove(page.id, -1) },
                onMoveLater = { onMove(page.id, 1) },
                modifier = Modifier.animateItem(),
            )
        }
        if (onAddPage != null) {
            item(key = ADD_TILE_KEY) {
                AddPageTile(
                    icon = Icons.Rounded.Add,
                    label = stringResource(R.string.feature_scan_review_add_short),
                    description = stringResource(R.string.feature_scan_review_add_page),
                    enabled = enabled,
                    onClick = onAddPage,
                )
            }
        }
        if (onAddPhotos != null) {
            item(key = ADD_PHOTOS_TILE_KEY) {
                AddPageTile(
                    icon = Icons.Rounded.AddPhotoAlternate,
                    label = stringResource(R.string.feature_scan_review_add_photos_short),
                    description = stringResource(R.string.feature_scan_review_add_photos),
                    enabled = enabled,
                    onClick = onAddPhotos,
                )
            }
        }
    }
}

@Composable
private fun StripThumbnail(
    page: StripPage,
    number: Int,
    total: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = remember(page.thumbnail) { page.thumbnail?.asImageBitmap() }
    val description = stringResource(R.string.feature_scan_page_label, number, total)
    val moveEarlier = stringResource(R.string.feature_scan_move_page_earlier, number)
    val moveLater = stringResource(R.string.feature_scan_move_page_later, number)
    val shape = MaterialTheme.shapes.extraSmall
    val borderColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        Motion.quick(),
        label = "stripBorder",
    )
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .semantics {
                contentDescription = description
                if (enabled) {
                    customActions = buildList {
                        if (number > 1) add(CustomAccessibilityAction(moveEarlier) { onMoveEarlier(); true })
                        if (number < total) add(CustomAccessibilityAction(moveLater) { onMoveLater(); true })
                    }
                }
            }
            .padding(Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(THUMB_WIDTH, THUMB_HEIGHT)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(width = if (selected) SELECTED_BORDER else HAIRLINE, color = borderColor, shape = shape),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            if (page.busy) {
                CircularProgressIndicator(modifier = Modifier.size(BUSY_SIZE), strokeWidth = 2.dp)
            }
        }
        Text(
            text = stringResource(R.string.feature_scan_number, number),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

@Composable
private fun AddPageTile(icon: ImageVector, label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
    val shape = MaterialTheme.shapes.extraSmall
    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(THUMB_WIDTH, THUMB_HEIGHT)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(HAIRLINE, MaterialTheme.colorScheme.outline, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = color)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** A horizontally scrolling row of [ToolButton]s, centred when they fit. */
@Composable
internal fun ToolRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Icon over a short label. The accessible name ([description]) says what it acts on. */
@Composable
internal fun ToolButton(
    icon: ImageVector,
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
    selected: Boolean = false,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
        destructive -> MaterialTheme.colorScheme.error
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .pressScale(interaction)
            .clip(MaterialTheme.shapes.medium)
            .clickable(interactionSource = interaction, indication = ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .widthIn(min = TOOL_MIN_WIDTH)
            .heightIn(min = MinTouchTarget)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = color)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** A tab of [EditingTabs]. */
internal data class EditingTab(@param:StringRes val label: Int, val icon: ImageVector)

/** The editing screens' bottom tabs (Material navigation bar: indicator, insets and semantics included). */
@Composable
internal fun EditingTabs(tabs: List<EditingTab>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(modifier = modifier, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        tabs.forEachIndexed { index, tab ->
            NavigationBarItem(
                selected = index == selected,
                onClick = { onSelect(index) },
                enabled = enabled,
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.label), maxLines = 1) },
            )
        }
    }
}

private val THUMB_WIDTH = 44.dp
private val THUMB_HEIGHT = 58.dp
private val TOOL_MIN_WIDTH = 64.dp
private val HAIRLINE = 1.dp
private val SELECTED_BORDER = 2.dp
private val BUSY_SIZE = 16.dp
private const val ADD_TILE_KEY = "add-page"
private const val ADD_PHOTOS_TILE_KEY = "add-photos"
private const val DISABLED_ALPHA = 0.38f
