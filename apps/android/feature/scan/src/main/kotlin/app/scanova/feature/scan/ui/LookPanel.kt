package app.scanova.feature.scan.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.scan.R
import app.scanova.feature.scan.filter.FilterCatalogue
import app.scanova.feature.scan.filter.PageFilter
import app.scanova.feature.scan.render.PageLook
import kotlin.math.roundToInt

/** What the look panel shows: the filter row or the adjustment sliders. */
internal enum class LookMode { FILTERS, ADJUST }

/**
 * Everything about a page's look, for the review and the editor: a switch
 * between filters and adjustments, "apply to all pages", and below it either
 * the row of live filter previews or the strength / brightness / contrast
 * sliders.
 *
 * Slider moves call [onLookChange] continuously (the page view composes them
 * live); [onLookChangeFinished] is called when the finger lifts, which is when
 * the page is rendered again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LookPanel(
    look: PageLook,
    thumbnails: Map<String, Bitmap>,
    mode: LookMode,
    enabled: Boolean,
    canApplyToAll: Boolean,
    onModeChange: (LookMode) -> Unit,
    onFilterSelected: (String) -> Unit,
    onLookChange: (PageLook) -> Unit,
    onLookChangeFinished: () -> Unit,
    onApplyToAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            val modes = LookMode.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                modes.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = mode == entry,
                        onClick = { onModeChange(entry) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                        label = {
                            Text(
                                stringResource(
                                    if (entry == LookMode.FILTERS) R.string.feature_scan_look_filters else R.string.feature_scan_look_adjust,
                                ),
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
            if (canApplyToAll) {
                QuietButton(
                    text = stringResource(R.string.feature_scan_look_apply_all),
                    onClick = onApplyToAll,
                    enabled = enabled,
                )
            }
        }
        AnimatedContent(
            targetState = mode,
            transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PANEL_MIN_HEIGHT),
            label = "lookMode",
        ) { shown ->
            when (shown) {
                LookMode.FILTERS -> FilterStrip(
                    selectedId = look.filterId,
                    thumbnails = thumbnails,
                    enabled = enabled,
                    onSelect = onFilterSelected,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
                LookMode.ADJUST -> AdjustSliders(
                    look = look,
                    enabled = enabled,
                    onChange = onLookChange,
                    onChangeFinished = onLookChangeFinished,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        }
    }
}

/** Every filter as a small live preview of the page itself. The chosen one is framed and marked. */
@Composable
internal fun FilterStrip(
    selectedId: String,
    thumbnails: Map<String, Bitmap>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filters = FilterCatalogue.all
    val listState = rememberLazyListState()
    // A page with a filter that sits off-screen (another page selected, say) brings it into view.
    LaunchedEffect(selectedId) {
        val index = filters.indexOfFirst { it.id == selectedId }
        if (index >= 0 && !listState.isFullyVisible(index)) listState.animateScrollToItem((index - 1).coerceAtLeast(0))
    }
    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        contentPadding = PaddingValues(horizontal = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        items(filters, key = { it.id }) { filter ->
            FilterItem(
                filter = filter,
                thumbnail = thumbnails[filter.id],
                selected = filter.id == selectedId,
                enabled = enabled,
                onClick = { onSelect(filter.id) },
            )
        }
    }
}

@Composable
private fun FilterItem(filter: PageFilter, thumbnail: Bitmap?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val image = remember(thumbnail) { thumbnail?.asImageBitmap() }
    val label = stringResource(filter.label)
    val accent = MaterialTheme.colorScheme.primary
    val border by animateColorAsState(if (selected) accent else Color.Transparent, Motion.quick(), label = "filterBorder")
    val scale by animateFloatAsState(if (selected) 1f else UNSELECTED_SCALE, Motion.bouncy(), label = "filterScale")
    val badge by animateFloatAsState(if (selected) 1f else 0f, Motion.quick(), label = "filterBadge")
    val textColor by animateColorAsState(
        if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        Motion.quick(),
        label = "filterText",
    )
    val shape = MaterialTheme.shapes.small
    Column(
        modifier = Modifier
            .width(ITEM_WIDTH)
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(THUMB_WIDTH, THUMB_HEIGHT)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(SELECTED_BORDER, border, shape),
        ) {
            if (image != null) {
                Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Spacing.xs)
                    .size(BADGE_SIZE)
                    .graphicsLayer {
                        alpha = badge
                        scaleX = badge
                        scaleY = badge
                    }
                    .background(accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(BADGE_ICON))
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) textColor else textColor.copy(alpha = DISABLED_ALPHA),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { },
        )
    }
}

/** Filter strength, brightness and contrast, with their values in words a screen reader can say. */
@Composable
internal fun AdjustSliders(
    look: PageLook,
    enabled: Boolean,
    onChange: (PageLook) -> Unit,
    onChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasFilter = look.filterId != FilterCatalogue.original.id
    Column(modifier = modifier.fillMaxWidth()) {
        AdjustRow(
            label = stringResource(R.string.feature_scan_adjust_strength),
            value = look.strength,
            range = 0f..1f,
            valueText = stringResource(R.string.feature_scan_percent, (look.strength * 100).roundToInt()),
            enabled = enabled && hasFilter,
            onChange = { onChange(look.copy(strength = it)) },
            onChangeFinished = onChangeFinished,
        )
        AdjustRow(
            label = stringResource(R.string.feature_scan_adjust_brightness),
            value = look.brightness,
            range = -1f..1f,
            valueText = stringResource(R.string.feature_scan_signed_number, (look.brightness * 100).roundToInt()),
            enabled = enabled,
            onChange = { onChange(look.copy(brightness = it)) },
            onChangeFinished = onChangeFinished,
        )
        AdjustRow(
            label = stringResource(R.string.feature_scan_adjust_contrast),
            value = look.contrast,
            range = -1f..1f,
            valueText = stringResource(R.string.feature_scan_signed_number, (look.contrast * 100).roundToInt()),
            enabled = enabled,
            onChange = { onChange(look.copy(contrast = it)) },
            onChangeFinished = onChangeFinished,
        )
        if (look.hasAdjustments) {
            QuietButton(
                text = stringResource(R.string.feature_scan_adjust_reset),
                onClick = {
                    onChange(look.withoutAdjustments())
                    onChangeFinished()
                },
                enabled = enabled,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun AdjustRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(LABEL_WIDTH),
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            onValueChangeFinished = onChangeFinished,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = label },
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(VALUE_WIDTH)
                .clearAndSetSemantics { },
        )
    }
}

internal fun LazyListState.isFullyVisible(index: Int): Boolean = layoutInfo.visibleItemsInfo.any { item ->
    item.index == index &&
        item.offset >= layoutInfo.viewportStartOffset &&
        item.offset + item.size <= layoutInfo.viewportEndOffset
}

private val ITEM_WIDTH = 76.dp
private val THUMB_WIDTH = 64.dp
private val THUMB_HEIGHT = 80.dp
private val SELECTED_BORDER = 2.dp
private val BADGE_SIZE = 18.dp
private val BADGE_ICON = 12.dp
private val LABEL_WIDTH = 88.dp
private val VALUE_WIDTH = 44.dp
private val PANEL_MIN_HEIGHT = 136.dp
private const val UNSELECTED_SCALE = 0.94f
private const val DISABLED_ALPHA = 0.38f
