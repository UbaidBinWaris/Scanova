package app.scanova.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// One confident blue on graphite neutrals (barely cool, no navy cast). The dark scheme is the reference
// design; the light scheme mirrors it for people who run their phone in light mode.
// Every text/background pair below meets WCAG AA (4.5:1 for body text).

private val Blue80 = Color(0xFF8AB4FF)
private val Blue90 = Color(0xFFD6E3FF)
private val Blue40 = Color(0xFF1F56C2)
private val Blue30 = Color(0xFF0B3D91)
private val Blue20 = Color(0xFF002E6A)
private val Blue10 = Color(0xFF001A43)

private val Slate80 = Color(0xFFB7C6E0)
private val Slate30 = Color(0xFF2F3F5C)
private val Slate20 = Color(0xFF21304A)

private val Cyan80 = Color(0xFF7FD0E8)
private val Cyan90 = Color(0xFFB4EBFF)
private val Cyan30 = Color(0xFF004E5E)
private val Cyan20 = Color(0xFF003641)

private val Night0 = Color(0xFF07080B)
private val Night5 = Color(0xFF0B0D12)
private val Night10 = Color(0xFF111318)
private val Night12 = Color(0xFF14161C)
private val Night15 = Color(0xFF181B22)
private val Night20 = Color(0xFF1E2129)
private val Night25 = Color(0xFF252932)
private val Night30 = Color(0xFF2A2E38)
private val Night40 = Color(0xFF3A3F4B)

private val Grey60 = Color(0xFF8A91A1)
private val Grey80 = Color(0xFFC2C7D4)
private val Grey90 = Color(0xFFE3E6EE)

internal val ScanovaDarkColors: ColorScheme = darkColorScheme(
    primary = Blue80,
    onPrimary = Blue20,
    primaryContainer = Blue30,
    onPrimaryContainer = Blue90,
    inversePrimary = Blue40,
    secondary = Slate80,
    onSecondary = Slate20,
    secondaryContainer = Slate30,
    onSecondaryContainer = Blue90,
    tertiary = Cyan80,
    onTertiary = Cyan20,
    tertiaryContainer = Cyan30,
    onTertiaryContainer = Cyan90,
    background = Night5,
    onBackground = Grey90,
    surface = Night10,
    onSurface = Grey90,
    surfaceVariant = Night30,
    onSurfaceVariant = Grey80,
    surfaceTint = Blue80,
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
    primary = Blue40,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Blue10,
    inversePrimary = Blue80,
    secondary = Color(0xFF535F78),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE3F2),
    onSecondaryContainer = Color(0xFF101C32),
    tertiary = Color(0xFF00677D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Cyan90,
    onTertiaryContainer = Color(0xFF001F27),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF171B26),
    surface = Color(0xFFF7F8FC),
    onSurface = Color(0xFF171B26),
    surfaceVariant = Color(0xFFE0E3EC),
    onSurfaceVariant = Color(0xFF434856),
    surfaceTint = Blue40,
    inverseSurface = Color(0xFF2C303B),
    inverseOnSurface = Color(0xFFEEF0F7),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF737886),
    outlineVariant = Color(0xFFC3C7D2),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF7F8FC),
    surfaceDim = Color(0xFFD7DAE3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F3F9),
    surfaceContainer = Color(0xFFEBEEF5),
    surfaceContainerHigh = Color(0xFFE5E8F0),
    surfaceContainerHighest = Color(0xFFDFE2EB),
)

/** Colours for sync status, readable on both schemes' surfaces. */
object StatusColors {
    val positiveDark = Color(0xFF7BDCA0)
    val positiveLight = Color(0xFF1B6B3C)
    val cautionDark = Color(0xFFFFCB7A)
    val cautionLight = Color(0xFF7A5200)
}
