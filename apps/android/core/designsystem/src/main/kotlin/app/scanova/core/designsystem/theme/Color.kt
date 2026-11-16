package app.scanova.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// "Emerald & graphite": one deep emerald brand colour on calm, neutral graphite
// greys (no blue cast), in a light and a dark scheme. Secondary is slate, the
// tertiary amber is a sparing warm accent. Every text/background pair below
// meets WCAG AA: 4.5:1 for text (primary even on the highest surface
// container), 3:1 for outlines. Re-check the ratios when changing a value.

// Emerald tones (hue ~162°). Emerald40 is as light as it can be while white
// text on it, and it as text on every light surface, stay above 4.5:1.
private val Emerald10 = Color(0xFF002117)
private val Emerald20 = Color(0xFF003828)
private val Emerald30 = Color(0xFF00513C)
private val Emerald40 = Color(0xFF0A7259)
private val Emerald80 = Color(0xFF5FD4AE)
private val Emerald90 = Color(0xFFBDF0DB)
private val Emerald95 = Color(0xFFD5F5E8)

// Slate (secondary): graphite with a faint cool tint.
private val Slate20 = Color(0xFF223038)
private val Slate40 = Color(0xFF4E5C65)
private val Slate80 = Color(0xFFB7C5CE)

// Amber (tertiary), used sparingly.
private val Amber10 = Color(0xFF261900)
private val Amber20 = Color(0xFF412D00)
private val Amber30 = Color(0xFF5D4200)
private val Amber40 = Color(0xFF7A5900)
private val Amber80 = Color(0xFFEFC16A)
private val Amber90 = Color(0xFFFFDFA0)

// Graphite neutrals for the dark scheme (R≈G≈B, a hair cool so they never look brown).
private val Night0 = Color(0xFF070808)
private val Night5 = Color(0xFF0C0D0E)
private val Night10 = Color(0xFF121314)
private val Night12 = Color(0xFF161718)
private val Night15 = Color(0xFF1A1C1D)
private val Night20 = Color(0xFF202223)
private val Night25 = Color(0xFF27292A)
private val Night30 = Color(0xFF2D3031)
private val Night40 = Color(0xFF3D4142)

// Neutral greys shared by both schemes.
private val Grey30 = Color(0xFF434849)
private val Grey50 = Color(0xFF707576)
private val Grey60 = Color(0xFF8B9091)
private val Grey80 = Color(0xFFC2C6C7)
private val Grey90 = Color(0xFFE2E4E4)
private val Ink = Color(0xFF181C1C)

// Paper: the light scheme's near-white neutrals.
private val Paper100 = Color(0xFFFFFFFF)
private val Paper98 = Color(0xFFF7F8F8)
private val Paper96 = Color(0xFFF1F2F2)
private val Paper94 = Color(0xFFECEDED)
private val Paper92 = Color(0xFFE6E8E8)
private val Paper90 = Color(0xFFE0E2E2)
private val Paper87 = Color(0xFFD8DADA)

internal val ScanovaDarkColors: ColorScheme = darkColorScheme(
    primary = Emerald80,
    onPrimary = Emerald20,
    primaryContainer = Emerald30,
    onPrimaryContainer = Emerald90,
    inversePrimary = Emerald40,
    secondary = Slate80,
    onSecondary = Slate20,
    secondaryContainer = Color(0xFF243630),
    onSecondaryContainer = Color(0xFFC9E9DB),
    tertiary = Amber80,
    onTertiary = Amber20,
    tertiaryContainer = Amber30,
    onTertiaryContainer = Amber90,
    background = Night5,
    onBackground = Grey90,
    surface = Night10,
    onSurface = Grey90,
    surfaceVariant = Night30,
    onSurfaceVariant = Grey80,
    surfaceTint = Emerald80,
    inverseSurface = Grey90,
    inverseOnSurface = Night20,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Grey60,
    outlineVariant = Night40,
    scrim = Color(0xFF000000),
    surfaceBright = Night25,
    surfaceDim = Night5,
    surfaceContainerLowest = Night0,
    surfaceContainerLow = Night12,
    surfaceContainer = Night15,
    surfaceContainerHigh = Night20,
    surfaceContainerHighest = Night25,
)

internal val ScanovaLightColors: ColorScheme = lightColorScheme(
    primary = Emerald40,
    onPrimary = Paper100,
    primaryContainer = Emerald95,
    onPrimaryContainer = Emerald10,
    inversePrimary = Emerald80,
    secondary = Slate40,
    onSecondary = Paper100,
    secondaryContainer = Color(0xFFDCEBE4),
    onSecondaryContainer = Color(0xFF0E2A20),
    tertiary = Amber40,
    onTertiary = Paper100,
    tertiaryContainer = Amber90,
    onTertiaryContainer = Amber10,
    background = Paper98,
    onBackground = Ink,
    surface = Paper98,
    onSurface = Ink,
    surfaceVariant = Paper90,
    onSurfaceVariant = Grey30,
    surfaceTint = Emerald40,
    inverseSurface = Color(0xFF2D3131),
    inverseOnSurface = Color(0xFFEFF1F1),
    error = Color(0xFFBA1A1A),
    onError = Paper100,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Grey50,
    outlineVariant = Color(0xFFC6CACA),
    scrim = Color(0xFF000000),
    surfaceBright = Paper98,
    surfaceDim = Paper87,
    surfaceContainerLowest = Paper100,
    surfaceContainerLow = Paper96,
    surfaceContainer = Paper94,
    surfaceContainerHigh = Paper92,
    surfaceContainerHighest = Paper90,
)

/**
 * Accent colours for tiles that need telling apart at a glance (quick actions,
 * tools, export formats). A deliberately small, calm family: the brand emerald
 * plus three muted companions. Tools are grouped by colour (capture, import,
 * documents, text) and told apart by their icons. Each pair is a tinted
 * container and the icon colour on it; the icon meets 4.5:1 on its tile and
 * on the card behind it, in both schemes.
 */
enum class Accent(
    internal val light: Pair<Color, Color>,
    internal val dark: Pair<Color, Color>,
) {
    /** The brand colour: scanning and capture. */
    EMERALD(light = Color(0xFFD5F2E6) to Color(0xFF0A6A52), dark = Color(0xFF16352B) to Color(0xFF6FD8B4)),

    /** Bringing things in: imported images and files. */
    TEAL(light = Color(0xFFD6EDF0) to Color(0xFF0B6170), dark = Color(0xFF133238) to Color(0xFF7BCFDC)),

    /** Working on documents (merge, split, share): the quiet graphite one. */
    SLATE(light = Color(0xFFE2E7EA) to Color(0xFF46545D), dark = Color(0xFF252C30) to Color(0xFFB5C3CC)),

    /** Text and enhancement. The warm accent; use it sparingly. */
    AMBER(light = Color(0xFFF8EACB) to Color(0xFF795400), dark = Color(0xFF392D14) to Color(0xFFEEC26E)),
    ;

    /** Tile background for this accent in the current scheme. */
    fun container(dark: Boolean): Color = if (dark) this.dark.first else light.first

    /** Icon colour on [container]. */
    fun content(dark: Boolean): Color = if (dark) this.dark.second else light.second
}

/** Colours for sync status, readable on both schemes' surfaces. */
object StatusColors {
    val positiveDark = Color(0xFF6FD8B4)
    val positiveLight = Color(0xFF0A6A52)
    val cautionDark = Color(0xFFEEC26E)
    val cautionLight = Color(0xFF795400)
}
