package app.scanova.feature.documents.common

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.scanova.core.data.model.Document
import app.scanova.core.designsystem.component.raisedCardColor
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.core.designsystem.theme.pressScale
import app.scanova.feature.documents.R
import kotlinx.coroutines.delay

/** Loads a document's cover; the screens pass [DocumentThumbnails.cover]. */
internal typealias CoverLoader = suspend (Document) -> Bitmap?

/** Whether a document item takes part in a multi-selection, and if so whether it is picked. */
internal enum class ItemSelection { NONE, UNSELECTED, SELECTED }

/**
 * A document as a row: its first page, title, date and pages, and its backup
 * badge. Tapping opens it; [onLongClick] (Files only) starts a selection.
 */
@Composable
internal fun DocumentListItem(
    document: Document,
    signedIn: Boolean,
    loadCover: CoverLoader,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selection: ItemSelection = ItemSelection.NONE,
    onLongClick: (() -> Unit)? = null,
) {
    DocumentSurface(selection = selection, onClick = onClick, onLongClick = onLongClick, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            DocumentCover(
                document = document,
                loadCover = loadCover,
                placeholderIconSize = Spacing.xl,
                modifier = Modifier
                    .size(width = ThumbnailWidth, height = ThumbnailHeight)
                    .clip(MaterialTheme.shapes.small)
                    .border(hairline(), MaterialTheme.shapes.small),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = documentDetails(document),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SyncBadge(syncBadgeKind(document, signedIn), Modifier.padding(top = Spacing.xs))
            }
            if (selection != ItemSelection.NONE) SelectionMark(selected = selection == ItemSelection.SELECTED)
        }
    }
}

/** A document as a grid cell: a large cover with the badge on it, the title and details under it. */
@Composable
internal fun DocumentGridItem(
    document: Document,
    signedIn: Boolean,
    loadCover: CoverLoader,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selection: ItemSelection = ItemSelection.NONE,
    onLongClick: (() -> Unit)? = null,
) {
    DocumentSurface(selection = selection, onClick = onClick, onLongClick = onLongClick, modifier = modifier) {
        Column {
            Box {
                DocumentCover(
                    document = document,
                    loadCover = loadCover,
                    placeholderIconSize = Spacing.xxl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(CELL_COVER_RATIO),
                )
                // On the cover, so every cell in a row keeps the same height; never under the check mark.
                SyncBadge(
                    syncBadgeKind(document, signedIn),
                    Modifier.padding(
                        start = Spacing.sm,
                        top = Spacing.sm,
                        end = if (selection == ItemSelection.NONE) Spacing.sm else Spacing.sm + SelectionMarkSize + Spacing.xs,
                    ),
                )
                if (selection != ItemSelection.NONE) {
                    SelectionMark(
                        selected = selection == ItemSelection.SELECTED,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(Spacing.sm),
                    )
                }
            }
            Column(
                modifier = Modifier.padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = documentDetails(document),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The card both item shapes share. While a selection is open the item behaves
 * as a checkbox (tap toggles), says whether it is picked, and is tinted when it is.
 */
@Composable
private fun DocumentSurface(
    selection: ItemSelection,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val selected = selection == ItemSelection.SELECTED
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) colors.secondaryContainer else raisedCardColor(),
        Motion.quick(),
        label = "itemContainer",
    )
    val borderColor by animateColorAsState(
        if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.6f),
        Motion.quick(),
        label = "itemBorder",
    )
    val shape = MaterialTheme.shapes.large
    val interaction = remember { MutableInteractionSource() }
    val selectedText = stringResource(R.string.feature_documents_selected)
    val notSelectedText = stringResource(R.string.feature_documents_not_selected)
    Surface(
        modifier = modifier
            .pressScale(interaction)
            .clip(shape)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(),
                role = if (selection == ItemSelection.NONE) Role.Button else Role.Checkbox,
                onLongClickLabel = if (onLongClick != null) stringResource(R.string.feature_documents_select_action) else null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .then(
                if (selection == ItemSelection.NONE) {
                    Modifier
                } else {
                    Modifier.semantics {
                        this.selected = selected
                        stateDescription = if (selected) selectedText else notSelectedText
                    }
                },
            ),
        shape = shape,
        color = container,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        content = content,
    )
}

/** The round check of a selectable item: filled with a tick when picked, an empty ring otherwise. */
@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val scale by animateFloatAsState(if (selected) 1f else 0f, Motion.bouncy(), label = "check")
    Box(
        modifier = modifier
            .size(SelectionMarkSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.9f))
            .border(BorderStroke(2.dp, MaterialTheme.colorScheme.outline), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = scale.coerceIn(0f, 1f)
                }
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(SelectionMarkSize * 0.7f),
            )
        }
    }
}

/**
 * The document's first page when it has page images on this phone, otherwise a
 * document symbol. Decorative: the title next to it names the document.
 */
@Composable
internal fun DocumentCover(
    document: Document,
    loadCover: CoverLoader,
    placeholderIconSize: Dp,
    modifier: Modifier = Modifier,
) {
    val cover by produceState<Bitmap?>(initialValue = null, document.id, document.updatedAt) { value = loadCover(document) }
    Crossfade(
        targetState = cover,
        animationSpec = Motion.quick(),
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        label = "cover",
    ) { bitmap ->
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // The top of a page usually says what it is.
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(placeholderIconSize),
                )
            }
        }
    }
}

// ── Loading placeholders ────────────────────────────────────────────────

/**
 * Placeholder rows while documents load. Reading the local database usually
 * takes a moment only, so nothing appears for the first instant.
 */
@Composable
internal fun ListSkeleton(rows: Int, modifier: Modifier = Modifier) {
    DelayedSkeleton(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            repeat(rows) { SkeletonRow() }
        }
    }
}

/** Placeholder cells for the grid layout. */
@Composable
internal fun GridSkeleton(rows: Int, modifier: Modifier = Modifier) {
    DelayedSkeleton(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            repeat(rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    SkeletonCell(Modifier.weight(1f))
                    SkeletonCell(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DelayedSkeleton(modifier: Modifier, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SKELETON_DELAY_MILLIS)
        visible = true
    }
    val description = stringResource(R.string.feature_documents_loading)
    Box(modifier.clearAndSetSemantics { contentDescription = description }) {
        if (visible) content()
    }
}

@Composable
private fun SkeletonRow() {
    Surface(shape = MaterialTheme.shapes.large, color = raisedCardColor(), border = hairline(), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Bone(Modifier.size(width = ThumbnailWidth, height = ThumbnailHeight))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Bone(
                    Modifier
                        .fillMaxWidth(0.6f)
                        .height(Spacing.md),
                )
                Bone(
                    Modifier
                        .fillMaxWidth(0.35f)
                        .height(Spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun SkeletonCell(modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.large, color = raisedCardColor(), border = hairline(), modifier = modifier) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(CELL_COVER_RATIO)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Bone(
                    Modifier
                        .fillMaxWidth(0.7f)
                        .height(Spacing.md),
                )
                Bone(
                    Modifier
                        .fillMaxWidth(0.45f)
                        .height(Spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun Bone(modifier: Modifier) {
    Box(
        modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/** Space between a list's items. */
internal val ItemSpacing = Spacing.sm

/** Padding around the lists on Home and Files. */
internal val ListPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.xl)

/** Grid cells grow from this width; two columns on a phone. */
internal val GridCellMinWidth = 156.dp

private const val SKELETON_DELAY_MILLIS = 150L

/** Grid covers show the top of the page, a little shorter than paper. */
private const val CELL_COVER_RATIO = 4f / 5f

private val ThumbnailWidth = 48.dp
private val ThumbnailHeight = 64.dp
private val SelectionMarkSize = 24.dp
