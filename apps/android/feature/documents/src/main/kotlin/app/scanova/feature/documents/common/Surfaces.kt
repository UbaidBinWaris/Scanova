package app.scanova.feature.documents.common

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.staggerDelay
import kotlinx.coroutines.delay

/** The hairline around cards and thumbnails. */
@Composable
@ReadOnlyComposable
internal fun hairline(): BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

/**
 * Whether items composed now should animate in: true for the first moments of
 * a screen, so its list staggers in once, not every time an item scrolls back.
 */
@Composable
internal fun rememberEntranceWindow(): Boolean {
    var open by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(ENTRANCE_WINDOW_MILLIS)
        open = false
    }
    return open
}

/** Fades and lifts an item in, a little after the one before it. Transform and alpha only. */
internal fun Modifier.staggeredEntrance(index: Int, enabled: Boolean): Modifier = if (!enabled) {
    this
} else {
    composed {
        val progress = remember { Animatable(0f) }
        LaunchedEffect(Unit) { progress.animateTo(1f, Motion.enter(staggerDelay(index))) }
        graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * ENTRANCE_LIFT_DP * density
        }
    }
}

private const val ENTRANCE_WINDOW_MILLIS = 400L
private const val ENTRANCE_LIFT_DP = 12f
