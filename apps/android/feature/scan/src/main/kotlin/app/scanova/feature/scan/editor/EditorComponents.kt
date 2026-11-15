package app.scanova.feature.scan.editor

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.scanova.core.data.model.MarkupStroke
import app.scanova.core.data.model.MarkupTool
import app.scanova.core.data.model.PagePoint
import app.scanova.core.designsystem.theme.MinTouchTarget
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.scan.R
import app.scanova.feature.scan.geometry.Point2
import app.scanova.feature.scan.geometry.RectD
import app.scanova.feature.scan.render.MarkupGeometry
import app.scanova.feature.scan.render.geometryKey
import app.scanova.feature.scan.render.look
import app.scanova.feature.scan.ui.LivePage
import app.scanova.feature.scan.ui.PageFrame
import app.scanova.feature.scan.ui.PageImage
import app.scanova.feature.scan.ui.PageNumberChip
import app.scanova.feature.scan.ui.ToolButton
import app.scanova.feature.scan.ui.ToolRow
import app.scanova.feature.scan.ui.drawMarkup
import kotlinx.coroutines.flow.drop

/**
 * The document's pages, large. The selected page is drawn from its live
 * layers with its markup; in the markup tab, one finger draws on it (and
 * swiping between pages is off).
 */
@Composable
internal fun EditorPager(
    state: EditorUiState,
    onPageSelected: (String) -> Unit,
    onPreviewNeeded: (String) -> Unit,
    onStroke: (List<PagePoint>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = state.pages
    val drawing = state.tab == EditorTab.MARKUP
    val pagerState = rememberPagerState(initialPage = state.selectedIndex) { pages.size }
    val currentPages by rememberUpdatedState(pages)
    var synced by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.drop(1).collect { index ->
            currentPages.getOrNull(index)?.let { onPageSelected(it.id) }
        }
    }
    LaunchedEffect(state.selectedIndex, pages.size) {
        if (state.selectedIndex in pages.indices && pagerState.currentPage != state.selectedIndex) {
            if (synced) {
                pagerState.animateScrollToPage(state.selectedIndex, animationSpec = Motion.enter())
            } else {
                pagerState.scrollToPage(state.selectedIndex)
            }
        }
        synced = true
    }
    val processingLabel = stringResource(R.string.feature_scan_page_processing)
    val failedLabel = stringResource(R.string.feature_scan_page_failed)
    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = Spacing.xl),
        pageSpacing = Spacing.lg,
        userScrollEnabled = !drawing,
        key = { index -> pages[index].id },
    ) { index ->
        val page = pages[index]
        LaunchedEffect(page.id, page.imageHandle) { onPreviewNeeded(page.id) }
        val layers = state.live.layers?.takeIf { it.pageId == page.id && it.geometry == page.renderEdit.geometryKey }
        val description = stringResource(R.string.feature_scan_editor_page_description, index + 1, pages.size)
        Box(modifier = Modifier.fillMaxSize()) {
            PageFrame(
                aspect = layers?.let { it.base.width.toFloat() / it.base.height } ?: page.aspect,
                processing = layers == null && page.id in state.rendering,
                failed = page.id in state.failed,
                processingLabel = processingLabel,
                failedLabel = failedLabel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = Spacing.xl),
            ) {
                if (layers != null) {
                    LivePage(
                        layers = layers,
                        look = page.edit.look,
                        markup = page.edit.markup,
                        contentDescription = description,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (drawing && page.id == state.selectedPageId) {
                        MarkupInput(
                            tool = state.markupTool,
                            color = MarkupPalette.colors(state.markupTool)[state.markupColor],
                            width = MarkupPalette.widths(state.markupTool)[state.markupWidth],
                            onStroke = onStroke,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    val image = state.previews[page.id]?.takeIf { it.handle == page.imageHandle }?.image ?: page.thumbnail
                    if (image != null) PageImage(bitmap = image, contentDescription = description, modifier = Modifier.fillMaxSize())
                }
            }
            if (pages.size > 1) {
                PageNumberChip(
                    text = stringResource(R.string.feature_scan_page_label, index + 1, pages.size),
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/**
 * Draws on the page: drag for a stroke, tap for a dot. The stroke in progress
 * is drawn here; finished strokes go to [onStroke] in fractions of the page.
 */
@Composable
private fun MarkupInput(
    tool: MarkupTool,
    color: Long,
    width: Float,
    onStroke: (List<PagePoint>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val points = remember { mutableStateListOf<PagePoint>() }
    val currentOnStroke by rememberUpdatedState(onStroke)
    val description = stringResource(R.string.feature_scan_markup_area_description)
    Canvas(
        modifier = modifier
            .semantics { contentDescription = description }
            .pointerInput(tool, color, width) {
                detectDragGestures(
                    onDragStart = { start ->
                        points.clear()
                        points += pagePoint(start, size)
                    },
                    onDrag = { change, _ ->
                        val next = pagePoint(change.position, size)
                        val aspect = size.width.toFloat() / size.height.coerceAtLeast(1)
                        if (MarkupGeometry.isNewPoint(points.lastOrNull(), next, aspect)) points += next
                        change.consume()
                    },
                    onDragEnd = {
                        currentOnStroke(points.toList())
                        points.clear()
                    },
                    onDragCancel = { points.clear() },
                )
            }
            .pointerInput(tool, color, width) {
                detectTapGestures(onTap = { position -> currentOnStroke(listOf(pagePoint(position, size))) })
            },
    ) {
        if (points.isNotEmpty()) drawMarkup(listOf(MarkupStroke(tool, color, width, points.toList())))
    }
}

private fun pagePoint(position: Offset, size: IntSize): PagePoint =
    MarkupGeometry.toPage(
        Point2(position.x.toDouble(), position.y.toDouble()),
        RectD(0.0, 0.0, size.width.toDouble(), size.height.toDouble()),
    )

/** Pen or highlighter, colour, width, and undo / redo / clear. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarkupTools(
    tool: MarkupTool,
    colorIndex: Int,
    widthIndex: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    canClear: Boolean,
    enabled: Boolean,
    onTool: (MarkupTool) -> Unit,
    onColor: (Int) -> Unit,
    onWidth: (Int) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val tools = MarkupTool.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                tools.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = tool == entry,
                        onClick = { onTool(entry) },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = tools.size),
                        icon = {
                            Icon(
                                if (entry == MarkupTool.PEN) Icons.Outlined.Draw else Icons.Outlined.Highlight,
                                contentDescription = null,
                                modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                            )
                        },
                        label = {
                            Text(
                                stringResource(if (entry == MarkupTool.PEN) R.string.feature_scan_markup_pen else R.string.feature_scan_markup_highlighter),
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
            IconButton(onClick = onUndo, enabled = enabled && canUndo) {
                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(R.string.feature_scan_markup_undo))
            }
            IconButton(onClick = onRedo, enabled = enabled && canRedo) {
                Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = stringResource(R.string.feature_scan_markup_redo))
            }
            IconButton(onClick = onClear, enabled = enabled && canClear) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.feature_scan_markup_clear))
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Row(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                MarkupPalette.colors(tool).forEachIndexed { index, color ->
                    ColorSwatch(
                        color = Color(color.toInt()),
                        name = stringResource(MarkupPalette.colorNames(tool)[index]),
                        selected = index == colorIndex,
                        enabled = enabled,
                        onClick = { onColor(index) },
                    )
                }
            }
            VerticalDivider(modifier = Modifier.height(DIVIDER_HEIGHT).padding(horizontal = Spacing.xs))
            Row(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                MarkupPalette.widthNames.forEachIndexed { index, name ->
                    WidthSwatch(
                        level = index,
                        name = stringResource(name),
                        selected = index == widthIndex,
                        enabled = enabled,
                        onClick = { onWidth(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorSwatch(color: Color, name: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val ring by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        Motion.quick(),
        label = "swatchRing",
    )
    val scale by animateFloatAsState(if (selected) 1f else UNSELECTED_SWATCH_SCALE, Motion.bouncy(), label = "swatchScale")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(MinTouchTarget)
            .clip(CircleShape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
    ) {
        Box(
            modifier = Modifier
                .size(SWATCH_SIZE)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .border(if (selected) 2.dp else 1.dp, ring, CircleShape)
                .padding(3.dp)
                .background(color, CircleShape),
        )
    }
}

@Composable
private fun WidthSwatch(level: Int, name: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        Motion.quick(),
        label = "widthTint",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(MinTouchTarget)
            .clip(CircleShape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
    ) {
        Box(
            modifier = Modifier
                .size(WIDTH_DOT_BASE + WIDTH_DOT_STEP * level)
                .background(tint, CircleShape),
        )
    }
}

/** Tools for the selected page: crop, turn, reorder, delete. */
@Composable
internal fun EditorPageTools(
    number: Int,
    total: Int,
    hasOriginal: Boolean,
    enabled: Boolean,
    onCrop: () -> Unit,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(vertical = Spacing.sm)) {
        ToolRow {
            ToolButton(
                icon = Icons.Outlined.Crop,
                label = stringResource(R.string.feature_scan_tool_crop),
                description = stringResource(R.string.feature_scan_review_adjust, number),
                enabled = enabled && hasOriginal,
                onClick = onCrop,
            )
            ToolButton(
                icon = Icons.AutoMirrored.Outlined.RotateLeft,
                label = stringResource(R.string.feature_scan_tool_rotate_left),
                description = stringResource(R.string.feature_scan_rotate_left_description, number),
                enabled = enabled,
                onClick = onRotateLeft,
            )
            ToolButton(
                icon = Icons.AutoMirrored.Outlined.RotateRight,
                label = stringResource(R.string.feature_scan_tool_rotate_right),
                description = stringResource(R.string.feature_scan_rotate_right_description, number),
                enabled = enabled,
                onClick = onRotateRight,
            )
            if (total > 1) {
                ToolButton(
                    icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    label = stringResource(R.string.feature_scan_tool_earlier),
                    description = stringResource(R.string.feature_scan_move_page_earlier, number),
                    enabled = enabled && number > 1,
                    onClick = onMoveEarlier,
                )
                ToolButton(
                    icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    label = stringResource(R.string.feature_scan_tool_later),
                    description = stringResource(R.string.feature_scan_move_page_later, number),
                    enabled = enabled && number < total,
                    onClick = onMoveLater,
                )
            }
            ToolButton(
                icon = Icons.Outlined.Delete,
                label = stringResource(R.string.feature_scan_tool_delete),
                description = stringResource(R.string.feature_scan_review_delete, number),
                enabled = enabled && total > 1,
                destructive = true,
                onClick = onDelete,
            )
        }
        if (!hasOriginal) {
            Text(
                text = stringResource(R.string.feature_scan_editor_no_original),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                    .widthIn(max = MESSAGE_MAX_WIDTH),
            )
        }
    }
}

private val SWATCH_SIZE = 30.dp
private val WIDTH_DOT_BASE = 6.dp
private val WIDTH_DOT_STEP = 6.dp
private val DIVIDER_HEIGHT = 28.dp
private val MESSAGE_MAX_WIDTH = 360.dp
private const val UNSELECTED_SWATCH_SCALE = 0.86f
