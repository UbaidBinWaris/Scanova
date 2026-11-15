package app.scanova.core.data.settings

import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.security.store.EncryptedKeyValueStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

enum class LibraryLayout { LIST, GRID }

data class AppSettings(
    /** Ask for fingerprint, face or screen lock before showing documents. */
    val appLockEnabled: Boolean = false,
    val libraryLayout: LibraryLayout = LibraryLayout.LIST,
    /** Take the picture automatically when the page is detected and held steady. */
    val autoCaptureEnabled: Boolean = true,
    /** The first-launch welcome screen was shown and dismissed. */
    val welcomeSeen: Boolean = false,
)

/** App preferences. Stored in the encrypted key-value file like everything else. */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setAppLockEnabled(enabled: Boolean)

    suspend fun setLibraryLayout(layout: LibraryLayout)

    suspend fun setAutoCaptureEnabled(enabled: Boolean)

    suspend fun setWelcomeSeen()
}

internal class SettingsRepositoryImpl(
    private val values: EncryptedKeyValueStore,
    private val dispatchers: AppDispatchers,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = values.observeAll()
        .map { stored ->
            AppSettings(
                appLockEnabled = stored[KEY_APP_LOCK] == TRUE,
                libraryLayout = if (stored[KEY_LAYOUT] == LibraryLayout.GRID.name) LibraryLayout.GRID else LibraryLayout.LIST,
                autoCaptureEnabled = stored[KEY_AUTO_CAPTURE] != FALSE,
                welcomeSeen = stored[KEY_WELCOME_SEEN] == TRUE,
            )
        }
        .distinctUntilChanged()
        .flowOn(dispatchers.io)

    override suspend fun setAppLockEnabled(enabled: Boolean) = put(KEY_APP_LOCK, enabled.toString())

    override suspend fun setLibraryLayout(layout: LibraryLayout) = put(KEY_LAYOUT, layout.name)

    override suspend fun setAutoCaptureEnabled(enabled: Boolean) = put(KEY_AUTO_CAPTURE, enabled.toString())

    override suspend fun setWelcomeSeen() = put(KEY_WELCOME_SEEN, "true")

    private suspend fun put(key: String, value: String) = withContext(dispatchers.io) { values.put(key, value) }

    private companion object {
        const val KEY_APP_LOCK = "settings.app_lock"
        const val KEY_LAYOUT = "settings.library_layout"
        const val KEY_AUTO_CAPTURE = "settings.auto_capture"
        const val KEY_WELCOME_SEEN = "settings.welcome_seen"
        const val TRUE = "true"
        const val FALSE = "false"
    }
}
