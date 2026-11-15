package app.scanova.feature.tools.common

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.scanova.core.data.model.Document
import app.scanova.core.designsystem.component.EmptyState
import app.scanova.core.designsystem.component.ErrorState
import app.scanova.core.designsystem.component.LoadingState
import app.scanova.core.designsystem.component.message
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.tools.R

/**
 * The tools' document picker: the library with a search field and covers.
 * With [selection] (ordered ids) it is a multi-select list whose marks show the
 * order things were picked in; without, a tap picks a single document.
 */
@Composable
internal fun DocumentPicker(
    state: DocumentListState,
    query: String,
    onQueryChange: (String) -> Unit,
    onDocumentClick: (Document) -> Unit,
    loadCover: suspend (String) -> Bitmap?,
    onScan: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    selection: List<String>? = null,
    contentPadding: PaddingValues = PaddingValues(),
    header: (LazyListScope.() -> Unit)? = null,
) {
    when (state) {
        DocumentListState.Loading -> LoadingState(modifier = modifier)
        is DocumentListState.Failed -> ErrorState(message = state.error.message(), onRetry = onRetry, modifier = modifier)
        is DocumentListState.Ready -> if (state.documents.isEmpty() && state.query.isBlank()) {
            EmptyState(
                icon = Icons.Outlined.Description,
                title = stringResource(R.string.feature_tools_picker_empty_title),
                body = stringResource(R.string.feature_tools_picker_empty_body),
                actionLabel = stringResource(R.string.feature_tools_scan_document),
                actionIcon = Icons.Outlined.DocumentScanner,
                onAction = onScan,
                modifier = modifier,
            )
        } else {
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                header?.invoke(this)
                item(key = "search", contentType = "search") {
                    SearchField(query, onQueryChange, Modifier.padding(bottom = Spacing.sm))
                }
                if (state.documents.isEmpty()) {
                    item(key = "no_results", contentType = "message") {
                        Text(
                            text = stringResource(R.string.feature_tools_picker_no_results, state.query.trim()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.lg),
                        )
                    }
                }
                items(state.documents, key = { it.id }, contentType = { "document" }) { document ->
                    val order = selection?.indexOf(document.id)?.takeIf { it >= 0 }?.plus(1)
                    PickerRow(
                        document = document,
                        multiSelect = selection != null,
                        selectedOrder = order,
                        loadCover = loadCover,
                        onClick = { onDocumentClick(document) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        placeholder = { Text(stringResource(R.string.feature_tools_picker_search)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.feature_tools_picker_search_clear))
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
    )
}

@Composable
private fun PickerRow(
    document: Document,
    multiSelect: Boolean,
    selectedOrder: Int?,
    loadCover: suspend (String) -> Bitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = selectedOrder != null
    val shape = MaterialTheme.shapes.large
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        animationSpec = Motion.quick(),
        label = "pickerBorder",
    )
    val interaction = remember { MutableInteractionSource() }
    val selectedText = selectedOrder?.let { stringResource(R.string.feature_tools_picker_selected_order, it) }
    val notSelectedText = stringResource(R.string.feature_tools_picker_not_selected)
    val action = if (multiSelect) {
        Modifier
            .toggleable(
                value = selected,
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Checkbox,
                onValueChange = { onClick() },
            )
            .semantics { stateDescription = selectedText ?: notSelectedText }
    } else {
        Modifier.clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClick = onClick)
    }
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(action),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            DocumentThumbnail(
                documentId = document.id,
                loadCover = loadCover,
                modifier = Modifier.size(width = ThumbnailWidth, height = ThumbnailHeight),
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
            }
            if (multiSelect) {
                SelectionMark(order = selectedOrder)
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A round mark: the pick order on the primary colour when chosen, an empty ring otherwise. Decorative. */
@Composable
internal fun SelectionMark(order: Int?, modifier: Modifier = Modifier) {
    val color by animateColorAsState(
        targetValue = if (order != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = Motion.quick(),
        label = "selectionMark",
    )
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = 2.dp,
                color = if (order != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (order != null) {
            Text(
                text = order.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/** A document's first page when it is on this phone, otherwise a document symbol. Decorative. */
@Composable
internal fun DocumentThumbnail(
    documentId: String,
    loadCover: suspend (String) -> Bitmap?,
    modifier: Modifier = Modifier,
    placeholderIconSize: Dp = Spacing.xl,
) {
    val cover by produceState<Bitmap?>(initialValue = null, documentId) { value = loadCover(documentId) }
    val shape = MaterialTheme.shapes.extraSmall
    Crossfade(
        targetState = cover,
        animationSpec = Motion.quick(),
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape),
        label = "thumbnail",
    ) { bitmap ->
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
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

internal val ThumbnailWidth = 48.dp
internal val ThumbnailHeight = 64.dp
