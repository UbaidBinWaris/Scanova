package app.scanova.feature.settings.lock

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.scanova.core.data.settings.SettingsRepository
import app.scanova.core.designsystem.component.EmptyState
import app.scanova.feature.settings.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppLockState(
    /** False until the setting has been read; nothing is shown before that. */
    val loaded: Boolean = false,
    val enabled: Boolean = false,
    val locked: Boolean = false,
)

/** State holder for the app lock: whether it is on, and whether the app is locked right now. */
@HiltViewModel
class AppLockViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(AppLockState())
    val state: StateFlow<AppLockState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings
                // Unreadable settings are handled by the app's start-up; do not lock anyone out here.
                .catch { mutableState.update { it.copy(loaded = true) } }
                .collect { settings ->
                    mutableState.update { current ->
                        current.copy(
                            loaded = true,
                            enabled = settings.appLockEnabled,
                            // Locked on a cold start; turning the lock on in Settings does not lock at once.
                            locked = if (current.loaded) current.locked && settings.appLockEnabled else settings.appLockEnabled,
                        )
                    }
                }
        }
    }

    fun onAppHidden() {
        mutableState.update { if (it.enabled) it.copy(locked = true) else it }
    }

    fun onUnlocked() {
        mutableState.update { it.copy(locked = false) }
    }
}

/**
 * Wraps the whole app. With the app lock on, [content] is not composed at all
 * until the owner has unlocked, and the app locks again whenever it leaves the
 * screen. While the lock is on, the window is also marked secure, so documents
 * do not appear in screenshots or in the recent-apps overview.
 */
@Composable
fun AppLockGate(viewModel: AppLockViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? FragmentActivity

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onAppHidden() }
    // FLAG_SECURE is set once by MainActivity for the whole app, lock or no lock.

    when {
        !state.loaded -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        state.locked -> {
            val promptTitle = stringResource(R.string.feature_settings_lock_prompt_title)
            val unlock = {
                when {
                    // The screen lock was removed from the phone: nothing can be asked for.
                    activity == null || !DeviceAuthenticator.isAvailable(activity) -> viewModel.onUnlocked()
                    else -> DeviceAuthenticator.authenticate(activity, promptTitle) { success ->
                        if (success) viewModel.onUnlocked()
                    }
                }
            }
            // Ask straight away; the button is there for a second attempt.
            LaunchedEffect(Unit) { unlock() }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize()) {
                    EmptyState(
                        icon = Icons.Outlined.Lock,
                        title = stringResource(R.string.feature_settings_lock_title),
                        body = stringResource(R.string.feature_settings_lock_body),
                        actionLabel = stringResource(R.string.feature_settings_lock_unlock),
                        onAction = unlock,
                    )
                }
            }
        }
        else -> content()
    }
}
