package app.scanova.feature.scan.review

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.scanova.core.designsystem.theme.MinTouchTarget
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.scan.R
import app.scanova.feature.scan.session.ScanFilter
import app.scanova.feature.scan.ui.labelRes
import kotlinx.coroutines.flow.drop

/**
 * The selected page, large. Swiping moves between pages; the selection made
 * anywhere else (page strip, new page, reordering) is followed here.
 */
@Composable
internal fun PagePager(
    state: ReviewUiState,
    onPageSelected: (String) -> Unit,
    onPreviewNeeded: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = state.pages
    val pagerState = rememberPagerState(initialPage = state.selectedIndex) { pages.size }
    val currentPages by rememberUpdatedState(pages)
    // Not saved on purpose: coming back to this screen, the pager jumps straight to the selection.
    var synced by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState) {
        // The first value is the position restored with the screen, not a choice the user made.
        snapshotFlow { pagerState.settledPage }.drop(1).collect { index ->
            currentPages.getOrNull(index)?.let { onPageSelected(it.id) }
        }
    }
    LaunchedEffect(state.selectedIndex, pages.size) {
        if (state.selectedIndex in pages.indices && pagerState.currentPage != state.selectedIndex) {
            if (synced) {
                pagerState.animateScrollToPage(state.selectedIndex, animationSpec = tween(PAGE_SCROLL_MILLIS))
            } else {
                pagerState.scrollToPage(state.selectedIndex)
            }
        }
        synced = true
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = Spacing.xl),
        pageSpacing = Spacing.lg,
        key = { index -> pages[index].id },
    ) { index ->
        val page = pages[index]
        LaunchedEffect(page.id, page.thumbnail) { onPreviewNeeded(page.id) }
        val preview = state.previews[page.id]?.takeIf { it.thumbnail === page.thumbnail }?.image
        PageView(
            thumbnail = page.thumbnail,
            preview = preview,
            number = index + 1,
            total = pages.size,
            changingLook = page.id in state.changingLook,
        )
    }
}

@Composable
private fun PageView(thumbnail: Bitmap, preview: Bitmap?, number: Int, total: Int, changingLook: Boolean) {
    // The thumbnail fills in until the sharper preview has been decoded.
    val bitmap = preview ?: thumbnail
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val ratio = thumbnail.width.toFloat() / thumbnail.height.coerceAtLeast(1)
    val shape = MaterialTheme.shapes.small
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .aspectRatio(ratio)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(HAIRLINE, MaterialTheme.colorScheme.outlineVariant, shape),
        ) {
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.feature_scan_review_page_image_description, number, total),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            AnimatedVisibility(
                visible = changingLook,
                enter = fadeIn(tween(MOTION_MILLIS)),
                exit = fadeOut(tween(MOTION_MILLIS)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = BUSY_SCRIM_ALPHA)),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(PROGRESS_SIZE), strokeWidth = 2.dp)
                            Text(
                                text = stringResource(R.string.feature_scan_review_changing_look),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }
        if (total > 1) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = Spacing.sm)
                    .clearAndSetSemantics { },
            ) {
                Text(
                    text = stringResource(R.string.feature_scan_review_page_label, number, total),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                )
            }
        }
    }
}

/** Tools for the selected page: reorder, crop, retake, delete. */
@Composable
internal fun PageTools(
    number: Int,
    total: Int,
    enabled: Boolean,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onCrop: () -> Unit,
    onRetake: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (total > 1) {
            ToolButton(
                icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                label = stringResource(R.string.feature_scan_review_tool_earlier),
                description = stringResource(R.string.feature_scan_review_move_earlier, number),
                enabled = enabled && number > 1,
                onClick = onMoveEarlier,
            )
        }
        ToolButton(
            icon = Icons.Outlined.Crop,
            label = stringResource(R.string.feature_scan_review_tool_crop),
            description = stringResource(R.string.feature_scan_review_adjust, number),
            enabled = enabled,
            onClick = onCrop,
        )
        ToolButton(
            icon = Icons.Outlined.CameraAlt,
            label = stringResource(R.string.feature_scan_review_tool_retake),
            description = stringResource(R.string.feature_scan_review_retake, number),
            enabled = enabled,
            onClick = onRetake,
        )
        ToolButton(
            icon = Icons.Outlined.Delete,
            label = stringResource(R.string.feature_scan_review_tool_delete),
            description = stringResource(R.string.feature_scan_review_delete, number),
            enabled = enabled,
            destructive = true,
            onClick = onDelete,
        )
        if (total > 1) {
            ToolButton(
                icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                label = stringResource(R.string.feature_scan_review_tool_later),
                description = stringResource(R.string.feature_scan_review_move_later, number),
                enabled = enabled && number < total,
                onClick = onMoveLater,
            )
        }
    }
}

/** Icon over a short label. The accessible name says which page it acts on. */
@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
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

/** The page's look: original, enhanced colour, greyscale or black and white. */
@Composable
internal fun LookChips(selected: ScanFilter, enabled: Boolean, onSelect: (ScanFilter) -> Unit) {
    val listState = rememberLazyListState()
    // A page with a look that sits off-screen (another page selected, say) brings it into view.
    LaunchedEffect(selected) {
        val index = ScanFilter.entries.indexOf(selected)
        if (!listState.isFullyVisible(index)) listState.animateScrollToItem(index)
    }
    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        contentPadding = PaddingValues(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(ScanFilter.entries, key = { it.name }) { filter ->
            val isSelected = filter == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(filter) },
                enabled = enabled,
                label = { Text(stringResource(filter.labelRes()), maxLines = 1) },
                leadingIcon = if (isSelected) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
            )
        }
    }
}

/** Every page as a small thumbnail, then a tile to add one more. */
@Composable
internal fun PageStrip(
    pages: List<ReviewPage>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onMove: (pageId: String, offset: Int) -> Unit,
    onAddPage: () -> Unit,
) {
    val listState = rememberLazyListState()
    // Keep the selected page in sight when it changes from elsewhere.
    LaunchedEffect(selectedIndex, pages.size) {
        if (!listState.isFullyVisible(selectedIndex)) listState.animateScrollToItem((selectedIndex - 1).coerceAtLeast(0))
    }
    LazyRow(
        state = listState,
        modifier = Modifier
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
        item(key = ADD_TILE_KEY) {
            AddPageTile(enabled = enabled, onClick = onAddPage)
        }
    }
}

@Composable
private fun StripThumbnail(
    page: ReviewPage,
    number: Int,
    total: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = remember(page.thumbnail) { page.thumbnail.asImageBitmap() }
    val description = stringResource(R.string.feature_scan_review_page_label, number, total)
    val moveEarlier = stringResource(R.string.feature_scan_review_move_earlier, number)
    val moveLater = stringResource(R.string.feature_scan_review_move_later, number)
    val shape = MaterialTheme.shapes.extraSmall
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
                .border(
                    width = if (selected) SELECTED_BORDER else HAIRLINE,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = shape,
                ),
        ) {
            Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
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
private fun AddPageTile(enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.feature_scan_review_add_page)
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
            Icon(imageVector = Icons.Outlined.Add, contentDescription = null, tint = color)
        }
        Text(
            text = stringResource(R.string.feature_scan_review_add_short),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

private fun LazyListState.isFullyVisible(index: Int): Boolean = layoutInfo.visibleItemsInfo.any { item ->
    item.index == index &&
        item.offset >= layoutInfo.viewportStartOffset &&
        item.offset + item.size <= layoutInfo.viewportEndOffset
}

private val THUMB_WIDTH = 48.dp
private val THUMB_HEIGHT = 64.dp
private val TOOL_MIN_WIDTH = 64.dp
private val HAIRLINE = 1.dp
private val SELECTED_BORDER = 2.dp
private val PROGRESS_SIZE = 16.dp
private const val ADD_TILE_KEY = "add-page"
private const val PAGE_SCROLL_MILLIS = 250
private const val BUSY_SCRIM_ALPHA = 0.32f
private const val DISABLED_ALPHA = 0.38f
