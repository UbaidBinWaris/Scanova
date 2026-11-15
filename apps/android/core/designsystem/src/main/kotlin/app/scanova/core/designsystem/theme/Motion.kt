package app.scanova.core.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Motion tokens. Every animation in the app takes its timing from here, so the
 * whole app moves with one rhythm: quick for feedback (≤ 200 ms), a little
 * longer for things entering the screen, never long enough to wait for.
 *
 * Only transform and opacity are animated (scale, offset, alpha), never layout
 * size, so animations stay smooth on slow phones.
 */
object Motion {
    /** Press feedback, toggles, small state changes. */
    const val SHORT_MS = 150

    /** Elements entering, cards expanding, tab content switching. */
    const val MEDIUM_MS = 250

    /** Screen-level transitions and the light/dark theme cross-fade. */
    const val LONG_MS = 350

    /** Delay between items of a list that animates in, one after another. */
    const val STAGGER_MS = 40

    /** Material 3 emphasized curves. */
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Feedback that must feel instant. */
    fun <T> quick(): FiniteAnimationSpec<T> = tween(SHORT_MS, easing = Standard)

    /** Something appearing: starts fast, settles gently. */
    fun <T> enter(delayMs: Int = 0): FiniteAnimationSpec<T> =
        tween(MEDIUM_MS, delayMillis = delayMs, easing = EmphasizedDecelerate)

    /** Something leaving: gets out of the way quickly. */
    fun <T> exit(): FiniteAnimationSpec<T> = tween(SHORT_MS, easing = EmphasizedAccelerate)

    /** A small, lively spring for things the finger moves (press, drag release, selection). */
    fun <T> bouncy(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)

    /** Scale of something being pressed. */
    const val PRESSED_SCALE = 0.95f
}

/**
 * Shrinks the element slightly while it is pressed and springs it back on
 * release. Pass the same [interactionSource] as the clickable it decorates.
 */
fun Modifier.pressScale(interactionSource: MutableInteractionSource): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PRESSED_SCALE else 1f,
        animationSpec = Motion.bouncy(),
        label = "pressScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Index-based delay for list items that animate in one after another (capped, so long lists don't lag). */
fun staggerDelay(index: Int): Int = (index.coerceAtMost(MAX_STAGGERED_ITEMS)) * Motion.STAGGER_MS

private const val MAX_STAGGERED_ITEMS = 8
