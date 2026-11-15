package app.scanova.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val ScanovaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Scanova's Material 3 theme. Designed dark-first; a phone set to light mode
 * gets the mirrored light scheme. Dynamic (wallpaper) colour is deliberately not
 * used, so the app looks the same on every device.
 */
@Composable
fun ScanovaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) ScanovaDarkColors else ScanovaLightColors,
        typography = ScanovaTypography,
        shapes = ScanovaShapes,
        content = content,
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
