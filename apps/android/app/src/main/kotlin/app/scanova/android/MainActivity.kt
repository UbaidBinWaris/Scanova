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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.scanova.core.data.serverai.ServerAiRepository
import app.scanova.core.data.settings.ThemeMode
import app.scanova.core.designsystem.theme.ScanovaTheme
import app.scanova.feature.settings.lock.AppLockGate
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/** The single activity. A FragmentActivity because the system's unlock prompt requires one. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val rootViewModel: RootViewModel by viewModels()
    private val themeViewModel: ThemeViewModel by viewModels()

    @Inject lateinit var serverAi: ServerAiRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // While the app is on screen, an owner's phone keeps the server AI loaded by checking in.
        // In the background (or closed) it stops, and the server unloads the AI 15 minutes later.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { serverAi.keepAliveWhileVisible() }
        }
        // Screenshots, recordings, casting and the recent-apps preview are allowed
        // unless the user turns on "Block screenshots" (Me → Security). The window
        // starts protected and opens up once the stored choice has been read, so
        // someone who blocks them is never exposed during start-up.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        // Only on a fresh start: a recreated activity must not replay the link.
        if (savedInstanceState == null) handlePairingLink(intent)
        setContent {
            val blockScreenshots by themeViewModel.blockScreenshots.collectAsStateWithLifecycle()
            LaunchedEffect(blockScreenshots) {
                when (blockScreenshots) {
                    false -> window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    true -> window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    null -> Unit // not read yet: stay protected
                }
            }
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
