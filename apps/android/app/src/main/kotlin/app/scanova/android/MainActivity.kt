package app.scanova.android

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.data.settings.ThemeMode
import app.scanova.core.designsystem.theme.ScanovaTheme
import app.scanova.feature.settings.lock.AppLockGate
import dagger.hilt.android.AndroidEntryPoint

/** The single activity. A FragmentActivity because the system's unlock prompt requires one. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val rootViewModel: RootViewModel by viewModels()
    private val themeViewModel: ThemeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always on, whatever the app-lock setting: documents must not end up in
        // screenshots, screen recordings, casting, or the recent-apps snapshot that
        // Android writes to disk outside the app's encryption.
        // Debug builds allow screenshots, so screens can be checked during development
        // (adb screencap). Every published (release) build is always protected.
        if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        // Only on a fresh start: a recreated activity must not replay the link.
        if (savedInstanceState == null) handlePairingLink(intent)
        setContent {
            val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Status and navigation bar icons follow the app's theme, not the phone's.
            LaunchedEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            ScanovaTheme(darkTheme = dark) {
                AppLockGate {
                    ScanovaApp(rootViewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingLink(intent)
    }

    private fun handlePairingLink(intent: Intent?) {
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        if (link.startsWith(PAIRING_LINK_PREFIX, ignoreCase = true)) rootViewModel.onPairingLink(link)
    }

    private companion object {
        const val PAIRING_LINK_PREFIX = "scanova://pair"
    }
}
