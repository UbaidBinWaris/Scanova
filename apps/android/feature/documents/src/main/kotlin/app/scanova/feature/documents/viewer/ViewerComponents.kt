package app.scanova.feature.documents.viewer

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.scanova.core.designsystem.component.raisedCardColor
import app.scanova.core.designsystem.theme.Accent
import app.scanova.core.designsystem.theme.IconSizes
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.ScanovaThemeInfo
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.core.designsystem.theme.pressScale
import app.scanova.feature.documents.R
import kotlinx.coroutines.launch

// ── Pages ───────────────────────────────────────────────────────────────

/** One page at a time, as a sheet of paper on the desk; swipe for the next. "2 / 5" under it. */
@Composable
internal fun PagePager(pages: ViewerContent.Pages, pagerState: PagerState, loadPage: PageLoader) {
    val count = pages.aspectRatios.size
    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Spacing.lg),
            pageSpacing = Spacing.lg,
            key = { index -> "${pages.version}:$index" },
        ) { index ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = Spacing.lg, bottom = if (count > 1) IndicatorClearance else Spacing.lg),
                contentAlignment = Alignment.Center,
            ) {
                val ratio = pages.aspectRatios.getOrElse(index) { 1f }.coerceIn(MIN_RATIO, MAX_RATIO)
                val fitsWidth = ratio >= maxWidth / maxHeight
                val pageWidth = if (fitsWidth) maxWidth else maxHeight * ratio
                val widthPx = with(LocalDensity.current) { pageWidth.roundToPx() }
                PageSheet(
                    index = index,
                    count = count,
                    version = pages.version,
                    widthPx = widthPx,
                    loadPage = loadPage,
                    modifier = Modifier
                        .width(pageWidth)
                        .aspectRatio(ratio),
                )
            }
        }
        if (count > 1) {
            PageIndicator(
                page = pagerState.currentPage + 1,
                count = count,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = Spacing.sm),
            )
        }
    }
}

@Composable
private fun PageSheet(index: Int, count: Int, version: Int, widthPx: Int, loadPage: PageLoader, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.feature_documents_viewer_page_description, index + 1, count)
    // null = still loading; a failed page is reported as text.
    val page by produceState<Result<Bitmap>?>(initialValue = null, index, widthPx, version) {
        val bitmap = loadPage(index, widthPx)
        value = if (bitmap != null) Result.success(bitmap) else Result.failure(IllegalStateException())
    }
    val shape = MaterialTheme.shapes.extraSmall
    Crossfade(
        targetState = page,
        animationSpec = Motion.quick(),
        modifier = modifier
            .shadow(PaperElevation, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        label = "page",
    ) { result ->
        val bitmap = result?.getOrNull()
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                bitmap != null -> Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = description,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                // Still loading: the empty sheet is enough.
                result == null -> Unit
                else -> Text(
                    text = stringResource(R.string.feature_documents_viewer_page_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(Spacing.lg),
                )
            }
        }
    }
}

/** "2 / 5". The page itself tells screen readers where they are, so this is decorative. */
@Composable
private fun PageIndicator(page: Int, count: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clearAndSetSemantics {},
        shape = CircleShape,
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Text(
            text = stringResource(R.string.feature_documents_viewer_page_short, page, count),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
        )
    }
}

/** Every page small, the one on screen outlined; tap one to go there. */
@Composable
internal fun ThumbnailStrip(pages: ViewerContent.Pages, pagerState: PagerState, loadPage: PageLoader) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val current = pagerState.currentPage
    LaunchedEffect(current) {
        val visible = listState.layoutInfo.visibleItemsInfo
        val fullyVisible = visible.any { it.index == current && it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }
        if (!fullyVisible) listState.animateScrollToItem((current - 1).coerceAtLeast(0))
    }
    val thumbHeightPx = with(LocalDensity.current) { ThumbnailHeight.roundToPx() }
    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(count = pages.aspectRatios.size, key = { index -> "${pages.version}:$index" }) { index ->
            val ratio = pages.aspectRatios[index].coerceIn(MIN_RATIO, MAX_RATIO)
            Thumbnail(
                index = index,
                count = pages.aspectRatios.size,
                ratio = ratio,
                version = pages.version,
                widthPx = (thumbHeightPx * ratio).toInt().coerceAtLeast(1),
                selected = index == current,
                loadPage = loadPage,
                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
            )
        }
    }
}

@Composable
private fun Thumbnail(
    index: Int,
    count: Int,
    ratio: Float,
    version: Int,
    widthPx: Int,
    selected: Boolean,
    loadPage: PageLoader,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.feature_documents_viewer_page_description, index + 1, count)
    val bitmap by produceState<Bitmap?>(initialValue = null, index, version, widthPx) { value = loadPage(index, widthPx) }
    val borderColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        Motion.quick(),
        label = "thumbBorder",
    )
    val scale by animateFloatAsState(if (selected) 1f else UNSELECTED_THUMB_SCALE, Motion.quick(), label = "thumbScale")
    val shape = MaterialTheme.shapes.extraSmall
    Box(
        modifier = Modifier
            .heightIn(min = ThumbnailHeight)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(ThumbnailHeight)
                .aspectRatio(ratio)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(BorderStroke(if (selected) 2.dp else 1.dp, borderColor), shape),
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

// ── Actions ─────────────────────────────────────────────────────────────

/**
 * The document's actions, always in reach of the thumb: Edit, Share and Text,
 * each in its own colour, and More for the rest.
 */
@Composable
internal fun ViewerActionBar(
    enabled: Boolean,
    canRetryUpload: Boolean,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onExtractText: () -> Unit,
    onRename: () -> Unit,
    onEditTags: () -> Unit,
    onSaveToDevice: () -> Unit,
    onRetryUpload: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = raisedCardColor()) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            ) {
                ViewerAction(
                    label = stringResource(R.string.feature_documents_edit),
                    icon = Icons.Rounded.Edit,
                    accent = Accent.EMERALD,
                    enabled = enabled,
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                )
                ViewerAction(
                    label = stringResource(R.string.feature_documents_share),
                    icon = Icons.Rounded.Share,
                    accent = Accent.TEAL,
                    enabled = enabled,
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                )
                ViewerAction(
                    label = stringResource(R.string.feature_documents_text),
                    icon = Icons.AutoMirrored.Rounded.TextSnippet,
                    accent = Accent.AMBER,
                    enabled = enabled,
                    onClick = onExtractText,
                    modifier = Modifier.weight(1f),
                )
                MoreAction(
                    enabled = enabled,
                    canRetryUpload = canRetryUpload,
                    onRename = onRename,
                    onEditTags = onEditTags,
                    onSaveToDevice = onSaveToDevice,
                    onRetryUpload = onRetryUpload,
                    onDelete = onDelete,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MoreAction(
    enabled: Boolean,
    canRetryUpload: Boolean,
    onRename: () -> Unit,
    onEditTags: () -> Unit,
    onSaveToDevice: () -> Unit,
    onRetryUpload: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        ViewerAction(
            label = stringResource(R.string.feature_documents_more),
            icon = Icons.Rounded.MoreHoriz,
            accent = null,
            enabled = enabled,
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            @Composable
            fun item(label: Int, icon: ImageVector, destructive: Boolean = false, action: () -> Unit) = DropdownMenuItem(
                text = { Text(stringResource(label)) },
                leadingIcon = { Icon(icon, contentDescription = null) },
                colors = if (destructive) {
                    MenuDefaults.itemColors(
                        textColor = MaterialTheme.colorScheme.error,
                        leadingIconColor = MaterialTheme.colorScheme.error,
                    )
                } else {
                    MenuDefaults.itemColors()
                },
                onClick = {
                    expanded = false
                    action()
                },
            )
            if (canRetryUpload) item(R.string.feature_documents_retry_upload, Icons.Rounded.CloudUpload, action = onRetryUpload)
            item(R.string.feature_documents_rename, Icons.Rounded.DriveFileRenameOutline, action = onRename)
            item(R.string.feature_documents_edit_tags, Icons.Rounded.Sell, action = onEditTags)
            item(R.string.feature_documents_save_to_device, Icons.Rounded.SaveAlt, action = onSaveToDevice)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            item(R.string.feature_documents_delete, Icons.Rounded.Delete, destructive = true, action = onDelete)
        }
    }
}

/** An icon on a small colour-coded pill with its label under it. The whole column is the button. */
@Composable
private fun ViewerAction(
    label: String,
    icon: ImageVector,
    accent: Accent?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = ScanovaThemeInfo.isDark
    val interaction = remember { MutableInteractionSource() }
    val container: Color = accent?.container(dark) ?: MaterialTheme.colorScheme.surfaceContainerHigh
    val content: Color = accent?.content(dark) ?: MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .heightIn(min = ActionHeight)
            .clip(MaterialTheme.shapes.medium)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = ripple(),
                onClick = onClick,
            )
            .pressScale(interaction)
            .padding(vertical = Spacing.sm)
            .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(width = ActionPillWidth, height = ActionPillHeight)
                .clip(CircleShape)
                .background(container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(IconSizes.bar))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val MIN_RATIO = 0.2f
private const val MAX_RATIO = 5f
private const val UNSELECTED_THUMB_SCALE = 0.92f
private const val DISABLED_ALPHA = 0.38f

private val PaperElevation = 2.dp

/** Room under the page for the "2 / 5" pill. */
private val IndicatorClearance = Spacing.xxl + Spacing.sm
private val ThumbnailHeight = 56.dp
private val ActionHeight = 64.dp
private val ActionPillWidth = 56.dp
private val ActionPillHeight = 32.dp
