package app.scanova.feature.scan.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.RotateLeft
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Delete
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
import androidx.compose.ui.res.stringResource
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.scan.R
import app.scanova.feature.scan.render.geometryKey
import app.scanova.feature.scan.ui.LivePage
import app.scanova.feature.scan.ui.PageFrame
import app.scanova.feature.scan.ui.PageImage
import app.scanova.feature.scan.ui.PageNumberChip
import app.scanova.feature.scan.ui.ToolButton
import app.scanova.feature.scan.ui.ToolRow
import kotlinx.coroutines.flow.drop

/**
 * The pages, large. Swiping moves between pages; the selection made anywhere
 * else (page strip, new page, reordering) is followed here. The selected page
 * is drawn from its live layers, so a new look shows at once.
 */
@Composable
internal fun ReviewPager(
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
        key = { index -> pages[index].id },
    ) { index ->
        val page = pages[index]
        LaunchedEffect(page.id, page.version) { onPreviewNeeded(page.id) }
        val layers = state.live.layers?.takeIf { it.pageId == page.id && it.geometry == page.edit.geometryKey }
        val description = stringResource(R.string.feature_scan_review_page_image_description, index + 1, pages.size)
        Box(modifier = Modifier.fillMaxSize()) {
            PageFrame(
                aspect = layers?.let { it.base.width.toFloat() / it.base.height } ?: page.aspect,
                processing = page.processing && layers == null,
                failed = page.failed,
                processingLabel = processingLabel,
                failedLabel = failedLabel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = Spacing.xl),
            ) {
                if (layers != null) {
                    LivePage(layers = layers, look = page.look, markup = emptyList(), contentDescription = description, modifier = Modifier.fillMaxSize())
                } else {
                    val image = state.previews[page.id]?.takeIf { it.version === page.version }?.image ?: page.thumbnail
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

/** Tools for the selected page: crop, turn, retake, reorder, delete. */
@Composable
internal fun ReviewPageTools(
    number: Int,
    total: Int,
    enabled: Boolean,
    onCrop: () -> Unit,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onRetake: () -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToolRow(modifier = modifier.padding(vertical = Spacing.sm)) {
        ToolButton(
            icon = Icons.Rounded.Crop,
            label = stringResource(R.string.feature_scan_tool_crop),
            description = stringResource(R.string.feature_scan_review_adjust, number),
            enabled = enabled,
            onClick = onCrop,
        )
        ToolButton(
            icon = Icons.AutoMirrored.Rounded.RotateLeft,
            label = stringResource(R.string.feature_scan_tool_rotate_left),
            description = stringResource(R.string.feature_scan_rotate_left_description, number),
            enabled = enabled,
            onClick = onRotateLeft,
        )
        ToolButton(
            icon = Icons.AutoMirrored.Rounded.RotateRight,
            label = stringResource(R.string.feature_scan_tool_rotate_right),
            description = stringResource(R.string.feature_scan_rotate_right_description, number),
            enabled = enabled,
            onClick = onRotateRight,
        )
        ToolButton(
            icon = Icons.Rounded.CameraAlt,
            label = stringResource(R.string.feature_scan_review_tool_retake),
            description = stringResource(R.string.feature_scan_review_retake, number),
            enabled = enabled,
            onClick = onRetake,
        )
        if (total > 1) {
            ToolButton(
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                label = stringResource(R.string.feature_scan_tool_earlier),
                description = stringResource(R.string.feature_scan_move_page_earlier, number),
                enabled = enabled && number > 1,
                onClick = onMoveEarlier,
            )
            ToolButton(
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                label = stringResource(R.string.feature_scan_tool_later),
                description = stringResource(R.string.feature_scan_move_page_later, number),
                enabled = enabled && number < total,
                onClick = onMoveLater,
            )
        }
        ToolButton(
            icon = Icons.Rounded.Delete,
            label = stringResource(R.string.feature_scan_tool_delete),
            description = stringResource(R.string.feature_scan_review_delete, number),
            enabled = enabled,
            destructive = true,
            onClick = onDelete,
        )
    }
}
