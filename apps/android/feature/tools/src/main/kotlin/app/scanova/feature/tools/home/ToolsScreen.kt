package app.scanova.feature.tools.home

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.scanova.core.data.model.AppTool
import app.scanova.core.designsystem.component.QuickActionTile
import app.scanova.core.designsystem.component.ScanovaCard
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.SectionHeader
import app.scanova.core.designsystem.theme.Accent
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.core.designsystem.theme.staggerDelay
import app.scanova.feature.tools.R

/** One tile of the Tools tab. */
private data class ToolTile(
    @StringRes val label: Int,
    val icon: ImageVector,
    val accent: Accent,
    val onClick: () -> Unit,
)

/** A titled group of tiles. */
private data class ToolGroup(@StringRes val title: Int, val tiles: List<ToolTile>)

/**
 * The Tools tab: every tool as a colour-coded tile, grouped by what it works
 * on. A tab screen, so no back arrow; the bottom bar's height arrives as
 * consumed padding from the app shell.
 */
@Composable
internal fun ToolsRoute(
    onScan: (idCard: Boolean) -> Unit,
    onTool: (AppTool) -> Unit,
    onShareAsImages: () -> Unit,
) {
    val groups = remember(onScan, onTool, onShareAsImages) {
        listOf(
            ToolGroup(
                R.string.feature_tools_group_scan,
                listOf(
                    ToolTile(R.string.feature_tools_scan_document, Icons.Outlined.DocumentScanner, Accent.BLUE) { onScan(false) },
                    ToolTile(R.string.feature_tools_id_card, Icons.Outlined.Badge, Accent.INDIGO) { onScan(true) },
                ),
            ),
            ToolGroup(
                R.string.feature_tools_group_import,
                listOf(
                    ToolTile(R.string.feature_tools_import_images, Icons.Outlined.PhotoLibrary, Accent.GREEN) {
                        onTool(AppTool.IMPORT_IMAGES)
                    },
                    ToolTile(R.string.feature_tools_import_files, Icons.Outlined.UploadFile, Accent.TEAL) {
                        onTool(AppTool.IMPORT_FILES)
                    },
                ),
            ),
            ToolGroup(
                R.string.feature_tools_group_pdf,
                listOf(
                    ToolTile(R.string.feature_tools_merge, Icons.AutoMirrored.Outlined.CallMerge, Accent.PURPLE) {
                        onTool(AppTool.MERGE_PDF)
                    },
                    ToolTile(R.string.feature_tools_split, Icons.Outlined.ContentCut, Accent.ORANGE) {
                        onTool(AppTool.SPLIT_PDF)
                    },
                    ToolTile(R.string.feature_tools_extract_text, Icons.AutoMirrored.Outlined.Notes, Accent.PINK) {
                        onTool(AppTool.EXTRACT_TEXT)
                    },
                    ToolTile(R.string.feature_tools_share_as_images, Icons.Outlined.Collections, Accent.RED, onShareAsImages),
                ),
            ),
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScanovaTopBar(title = stringResource(R.string.feature_tools_title)) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // The two small groups share a row, two tiles each; very narrow screens stack everything.
            val narrow = maxWidth < 360.dp
            val columns = if (narrow) 3 else 4
            val (paired, single) = if (narrow) emptyList<ToolGroup>() to groups else groups.take(2) to groups.drop(2)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.lg,
                    end = Spacing.lg,
                    top = padding.calculateTopPadding() + Spacing.sm,
                    bottom = padding.calculateBottomPadding() + Spacing.xl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item(key = "intro") {
                    Text(
                        text = stringResource(R.string.feature_tools_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .widthIn(max = MaxContentWidth)
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xs),
                    )
                }
                if (paired.isNotEmpty()) {
                    item(key = "paired") {
                        Row(
                            modifier = Modifier
                                .widthIn(max = MaxContentWidth)
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            paired.forEachIndexed { index, group ->
                                ToolSection(
                                    group = group,
                                    columns = 2,
                                    index = index,
                                    fillHeight = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                )
                            }
                        }
                    }
                }
                itemsIndexed(single, key = { _, group -> group.title }) { index, group ->
                    ToolSection(
                        group = group,
                        columns = columns,
                        index = paired.size + index,
                        modifier = Modifier
                            .widthIn(max = MaxContentWidth)
                            .fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolSection(
    group: ToolGroup,
    columns: Int,
    index: Int,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
) {
    // Sections rise into place one after another when the tab opens.
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, Motion.enter(staggerDelay(index))) }
    val rise = with(LocalDensity.current) { Spacing.md.toPx() }
    Column(
        modifier = modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * rise
        },
    ) {
        SectionHeader(stringResource(group.title))
        ScanovaCard(
            // Side by side, both cards take the height of the taller one.
            modifier = if (fillHeight) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.md),
        ) {
            group.tiles.chunked(columns).forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) Spacer(Modifier.padding(top = Spacing.sm))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    row.forEach { tile ->
                        QuickActionTile(
                            label = stringResource(tile.label),
                            icon = tile.icon,
                            accent = tile.accent,
                            onClick = tile.onClick,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keep tiles the same width in a short last row.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private val MaxContentWidth = 720.dp
