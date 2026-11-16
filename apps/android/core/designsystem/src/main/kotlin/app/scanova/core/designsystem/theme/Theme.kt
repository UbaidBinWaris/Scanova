package app.scanova.core.designsystem.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Corner radii on the 4 dp grid, crisp rather than bubbly: pages, fields,
// menus and badges (4), thumbnails, chips and banners (8), icon tiles (12),
// cards and quick-action tiles (16), sheets and dialogs (28).
private val ScanovaShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val LocalDarkTheme = staticCompositionLocalOf { true }

/** Whether the dark scheme is showing. For colours outside [MaterialTheme.colorScheme] (e.g. [Accent]). */
object ScanovaThemeInfo {
    val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalDarkTheme.current
}

/**
 * Scanova's Material 3 theme, in a light and a dark scheme (the user picks one,
 * or follows the phone). Switching cross-fades every colour instead of
 * flashing. Dynamic (wallpaper) colour is deliberately not used, so the app
 * looks the same on every device.
 */
@Composable
fun ScanovaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val target = if (darkTheme) ScanovaDarkColors else ScanovaLightColors
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = target.animated(),
            typography = ScanovaTypography,
            shapes = ScanovaShapes,
            content = content,
        )
    }
}

/** Every colour of the scheme animates to its new value when the theme changes. */
@Composable
private fun ColorScheme.animated(): ColorScheme {
    @Composable
    fun Color.a(label: String): Color =
        animateColorAsState(this, tween(Motion.LONG_MS, easing = Motion.Standard), label = label).value

    return copy(
        primary = primary.a("primary"),
        onPrimary = onPrimary.a("onPrimary"),
        primaryContainer = primaryContainer.a("primaryContainer"),
        onPrimaryContainer = onPrimaryContainer.a("onPrimaryContainer"),
        inversePrimary = inversePrimary.a("inversePrimary"),
        secondary = secondary.a("secondary"),
        onSecondary = onSecondary.a("onSecondary"),
        secondaryContainer = secondaryContainer.a("secondaryContainer"),
        onSecondaryContainer = onSecondaryContainer.a("onSecondaryContainer"),
        tertiary = tertiary.a("tertiary"),
        onTertiary = onTertiary.a("onTertiary"),
        tertiaryContainer = tertiaryContainer.a("tertiaryContainer"),
        onTertiaryContainer = onTertiaryContainer.a("onTertiaryContainer"),
        background = background.a("background"),
        onBackground = onBackground.a("onBackground"),
        surface = surface.a("surface"),
        onSurface = onSurface.a("onSurface"),
        surfaceVariant = surfaceVariant.a("surfaceVariant"),
        onSurfaceVariant = onSurfaceVariant.a("onSurfaceVariant"),
        surfaceTint = surfaceTint.a("surfaceTint"),
        inverseSurface = inverseSurface.a("inverseSurface"),
        inverseOnSurface = inverseOnSurface.a("inverseOnSurface"),
        error = error.a("error"),
        onError = onError.a("onError"),
        errorContainer = errorContainer.a("errorContainer"),
        onErrorContainer = onErrorContainer.a("onErrorContainer"),
        outline = outline.a("outline"),
        outlineVariant = outlineVariant.a("outlineVariant"),
        scrim = scrim,
        surfaceBright = surfaceBright.a("surfaceBright"),
        surfaceDim = surfaceDim.a("surfaceDim"),
        surfaceContainerLowest = surfaceContainerLowest.a("surfaceContainerLowest"),
        surfaceContainerLow = surfaceContainerLow.a("surfaceContainerLow"),
        surfaceContainer = surfaceContainer.a("surfaceContainer"),
        surfaceContainerHigh = surfaceContainerHigh.a("surfaceContainerHigh"),
        surfaceContainerHighest = surfaceContainerHighest.a("surfaceContainerHighest"),
    )
}

/** Spacing steps on a 4 dp grid. Screens use these instead of ad-hoc numbers. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Smallest touch target allowed anywhere in the app. */
val MinTouchTarget = 48.dp

/**
 * Icon sizes. Every icon is a Material Rounded icon at one of these sizes, so
 * screens look like one product.
 */
object IconSizes {
    /** Inside chips, pills, badges and one-line notes. */
    val small = 16.dp

    /** On the 36–40 dp tiles of list rows and cards. */
    val inRowTile = 20.dp

    /** Top bars, the bottom bar, action bars, menus and plain rows. */
    val bar = 24.dp

    /** On the large tiles of quick actions and tools. */
    val tile = 26.dp
}
