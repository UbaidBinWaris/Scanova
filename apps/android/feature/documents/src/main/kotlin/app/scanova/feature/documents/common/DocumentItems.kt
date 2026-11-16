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
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ElectricBolt
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.RequestQuote
import androidx.compose.material.icons.rounded.WorkspacePremium
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import app.scanova.core.designsystem.component.BadgeTone
import app.scanova.core.designsystem.component.StatusBadge
import app.scanova.core.designsystem.component.raisedCardColor
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.core.designsystem.theme.pressScale
import app.scanova.core.ml.insight.DocumentType
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
                val type = documentType(document)
                val syncKind = syncBadgeKind(document, signedIn)
                if (type != null || syncKind != null) {
                    // Wraps on a narrow screen instead of cutting the second badge off.
                    FlowRow(
                        modifier = Modifier.padding(top = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        if (type != null) TypeBadge(type)
                        SyncBadge(syncKind)
                    }
                }
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
                // The type leads the details line, so every cell in a row keeps the same height.
                val details = documentDetails(document)
                val type = documentType(document)
                Text(
                    text = if (type == null) details else stringResource(R.string.feature_documents_item_type_details, typeLabel(type), details),
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
 * The kind of document, recognised on the phone from its text (or set by the
 * user as a tag): receipt, invoice, bill… Null for anything else.
 */
internal fun documentType(document: Document): DocumentType? = DocumentType.fromTags(document.tags)

/** The document's type as a quiet badge: an icon and a word. */
@Composable
internal fun TypeBadge(type: DocumentType, modifier: Modifier = Modifier) {
    StatusBadge(label = typeLabel(type), icon = typeIcon(type), tone = BadgeTone.Neutral, modifier = modifier)
}

@Composable
internal fun typeLabel(type: DocumentType): String = stringResource(
    when (type) {
        DocumentType.RECEIPT -> R.string.feature_documents_type_receipt
        DocumentType.INVOICE -> R.string.feature_documents_type_invoice
        DocumentType.BILL -> R.string.feature_documents_type_bill
        DocumentType.ID_CARD -> R.string.feature_documents_type_id_card
        DocumentType.CERTIFICATE -> R.string.feature_documents_type_certificate
        DocumentType.LETTER -> R.string.feature_documents_type_letter
        DocumentType.FORM -> R.string.feature_documents_type_form
        DocumentType.BANK_STATEMENT -> R.string.feature_documents_type_bank_statement
        DocumentType.PRESCRIPTION -> R.string.feature_documents_type_prescription
        DocumentType.TICKET -> R.string.feature_documents_type_ticket
        // Never a tag (fromTags does not return it); named only for completeness.
        DocumentType.OTHER -> R.string.feature_documents_type_other
    },
)

private fun typeIcon(type: DocumentType): ImageVector = when (type) {
    DocumentType.RECEIPT -> Icons.Rounded.Receipt
    DocumentType.INVOICE -> Icons.Rounded.RequestQuote
    DocumentType.BILL -> Icons.Rounded.ElectricBolt
    DocumentType.ID_CARD -> Icons.Rounded.Badge
    DocumentType.CERTIFICATE -> Icons.Rounded.WorkspacePremium
    DocumentType.LETTER -> Icons.Rounded.Mail
    DocumentType.FORM -> Icons.AutoMirrored.Rounded.Assignment
    DocumentType.BANK_STATEMENT -> Icons.Rounded.AccountBalance
    DocumentType.PRESCRIPTION -> Icons.Rounded.Medication
    DocumentType.TICKET -> Icons.Rounded.ConfirmationNumber
    DocumentType.OTHER -> Icons.Rounded.Description
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
                imageVector = Icons.Rounded.Check,
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
                    imageVector = Icons.Rounded.Description,
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
