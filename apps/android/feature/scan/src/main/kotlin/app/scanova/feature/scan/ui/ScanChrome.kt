package app.scanova.feature.scan.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.scanova.core.designsystem.theme.ScanovaThemeInfo

/**
 * Light status and navigation bar icons while the caller is shown, for screens
 * that are always dark (the camera), whatever the theme. The icons follow the
 * app's theme again when the caller leaves — the app's, not the phone's: someone
 * may run the app in light mode on a phone set to dark.
 */
@Composable
internal fun LightSystemBarIcons() {
    val view = LocalView.current
    val lightIconsAfterwards = !ScanovaThemeInfo.isDark
    if (view.isInEditMode) return
    DisposableEffect(view, lightIconsAfterwards) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.isAppearanceLightStatusBars = lightIconsAfterwards
            controller?.isAppearanceLightNavigationBars = lightIconsAfterwards
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
