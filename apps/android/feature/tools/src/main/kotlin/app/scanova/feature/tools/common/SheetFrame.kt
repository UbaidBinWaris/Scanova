package app.scanova.feature.tools.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.Spacing
import kotlinx.coroutines.delay

/**
 * Window settings for a navigation `dialog` destination drawn as a bottom
 * sheet by [SheetFrame]: edge to edge, full width, so the frame can place the
 * sheet at the bottom and draw its own scrim. Screen security is inherited
 * from the activity (no screenshots).
 */
internal val SheetDialogProperties = DialogProperties(
    dismissOnBackPress = true,
    dismissOnClickOutside = false,
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = false,
)

/** Shows and hides a [SheetFrame] with its slide animation. */
@Stable
internal class SheetVisibility {
    val transition = MutableTransitionState(false).apply { targetState = true }

    /** Slides the sheet away; returns once it is gone, so the destination can be popped. */
    suspend fun hide() {
        transition.targetState = false
        delay(Motion.SHORT_MS.toLong())
    }
}

@Composable
internal fun rememberSheetVisibility(): SheetVisibility = remember { SheetVisibility() }

/**
 * A modal bottom sheet inside a navigation dialog: a dimmed scrim (tap to
 * dismiss) and a rounded sheet that slides up, scrolls when taller than the
 * screen and keeps clear of the system bars.
 */
@Composable
internal fun SheetFrame(
    visibility: SheetVisibility,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // The frame draws its own scrim, animated with the sheet.
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { window?.setDimAmount(0f) }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visibleState = visibility.transition,
            enter = fadeIn(Motion.enter()),
            exit = fadeOut(Motion.exit()),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
                    // Not a button for screen readers: the sheet has its own close action.
                    .pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } },
            )
        }
        AnimatedVisibility(
            visibleState = visibility.transition,
            enter = slideInVertically(Motion.enter()) { it / 2 } + fadeIn(Motion.enter()),
            exit = slideOutVertically(Motion.exit()) { it / 2 } + fadeOut(Motion.exit()),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = Spacing.xl),
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp,
                modifier = Modifier
                    .widthIn(max = MaxSheetWidth)
                    .fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.xl)
                        .padding(bottom = Spacing.lg),
                ) {
                    DragHandle(Modifier.align(Alignment.CenterHorizontally))
                    content()
                }
            }
        }
    }
}

@Composable
private fun DragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(top = Spacing.md, bottom = Spacing.sm)
            .size(width = 32.dp, height = 4.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
    )
}

private const val SCRIM_ALPHA = 0.45f
private val MaxSheetWidth = 640.dp
