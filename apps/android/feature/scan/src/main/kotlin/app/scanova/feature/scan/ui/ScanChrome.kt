package app.scanova.feature.scan.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.scanova.feature.scan.R
import app.scanova.feature.scan.session.ScanFilter

/**
 * Light status and navigation bar icons while the caller is shown, for screens
 * that are always dark (the camera), whatever the system theme. The icons
 * follow the system theme again when the caller leaves.
 */
@Composable
internal fun LightSystemBarIcons() {
    val view = LocalView.current
    val lightIconsAfterwards = !isSystemInDarkTheme()
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

@StringRes
internal fun ScanFilter.labelRes(): Int = when (this) {
    ScanFilter.ORIGINAL -> R.string.feature_scan_filter_original
    ScanFilter.ENHANCED -> R.string.feature_scan_filter_enhanced
    ScanFilter.GREYSCALE -> R.string.feature_scan_filter_greyscale
    ScanFilter.BLACK_WHITE -> R.string.feature_scan_filter_black_white
}
