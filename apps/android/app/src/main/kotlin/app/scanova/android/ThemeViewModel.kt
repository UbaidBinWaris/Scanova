package app.scanova.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.data.settings.SettingsRepository
import app.scanova.core.data.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Window-wide preferences from Settings: the light/dark choice and screenshot blocking. */
@HiltViewModel
class ThemeViewModel @Inject constructor(settings: SettingsRepository) : ViewModel() {
    val themeMode: StateFlow<ThemeMode> = settings.settings
        .map { it.themeMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    /**
     * Whether to block screenshots; null until the stored choice has been read,
     * so the window can stay protected until then. The app lock implies it: a
     * locked app must not show documents in the recent-apps preview.
     */
    val blockScreenshots: StateFlow<Boolean?> = settings.settings
        .map<_, Boolean?> { it.blockScreenshots || it.appLockEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
