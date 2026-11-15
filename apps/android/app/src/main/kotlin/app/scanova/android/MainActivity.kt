package app.scanova.android

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import app.scanova.core.designsystem.theme.ScanovaTheme
import app.scanova.feature.settings.lock.AppLockGate
import dagger.hilt.android.AndroidEntryPoint

/** The single activity. A FragmentActivity because the system's unlock prompt requires one. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val rootViewModel: RootViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always on, whatever the app-lock setting: documents must not end up in
        // screenshots, screen recordings, casting, or the recent-apps snapshot that
        // Android writes to disk outside the app's encryption.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        // Only on a fresh start: a recreated activity must not replay the link.
        if (savedInstanceState == null) handlePairingLink(intent)
        setContent {
            ScanovaTheme {
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
