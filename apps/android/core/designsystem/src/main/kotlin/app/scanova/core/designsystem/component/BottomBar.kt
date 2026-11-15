package app.scanova.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.pressScale

/** One destination of the bottom bar. */
data class BottomBarItem(
    val label: String,
    val icon: ImageVector,
    /** Shown while the destination is selected (usually the filled variant of [icon]). */
    val selectedIcon: ImageVector = icon,
)

/**
 * The app's main navigation: four destinations, two on each side of a raised,
 * round Scan button, the one action the whole app is about.
 *
 * The selected destination gets a pill behind its icon that grows in, and its
 * icon cross-fades to the filled variant. Handles the system navigation bar
 * inset itself.
 *
 * @param items exactly four; the first two go left of the Scan button.
 */
@Composable
fun ScanovaBottomBar(
    items: List<BottomBarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    scanIcon: ImageVector,
    scanLabel: String,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    require(items.size == 4) { "ScanovaBottomBar takes four items" }
    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = ScanButtonRaise),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        ) {
            Row(
                modifier = Modifier
                    .navigationBarsPadding()
                    .height(BarHeight)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, item ->
                    if (index == 2) Spacer(Modifier.weight(1f)) // room for the Scan button
                    BarItem(
                        item = item,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        ScanButton(
            icon = scanIcon,
            label = scanLabel,
            onClick = onScan,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun BarItem(
    item: BottomBarItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val indicator by animateFloatAsState(if (selected) 1f else 0f, Motion.bouncy(), label = "indicator")
    val contentColor by animateColorAsState(
        if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        Motion.quick(),
        label = "itemColor",
    )
    Column(
        modifier = modifier
            .height(BarHeight)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = 32.dp),
            )
            .pressScale(interaction),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier
                    .size(width = 56.dp, height = 30.dp)
                    .graphicsLayer {
                        alpha = indicator.coerceIn(0f, 1f)
                        scaleX = 0.5f + 0.5f * indicator
                    },
                shape = CircleShape,
                color = colors.secondaryContainer,
            ) {}
            AnimatedContent(
                targetState = selected,
                transitionSpec = { fadeIn(Motion.quick()) togetherWith fadeOut(Motion.quick()) },
                label = "icon",
            ) { isSelected ->
                Icon(
                    imageVector = if (isSelected) item.selectedIcon else item.icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ScanButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(ScanButtonSize)
            .pressScale(interaction)
            .semantics { contentDescription = label },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 6.dp,
        interactionSource = interaction,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(28.dp))
        }
    }
}

private val BarHeight = 64.dp
private val ScanButtonSize = 60.dp

/** How far the Scan button sticks out above the bar. */
private val ScanButtonRaise = 18.dp
