package app.scanova.core.data.settings

import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.data.model.PageFilters
import app.scanova.core.security.store.EncryptedKeyValueStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

enum class LibraryLayout { LIST, GRID }

/** Light, dark, or whatever the phone uses. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The camera's flash setting. */
enum class FlashMode { OFF, AUTO, ON, TORCH }

data class AppSettings(
    /** Ask for fingerprint, face or screen lock before showing documents. */
    val appLockEnabled: Boolean = false,
    val libraryLayout: LibraryLayout = LibraryLayout.LIST,
    /** Take the picture automatically when the page is detected and held steady. Off unless the user turns it on. */
    val autoCaptureEnabled: Boolean = false,
    /** The first-launch welcome screen was shown and dismissed. */
    val welcomeSeen: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val cameraFlash: FlashMode = FlashMode.OFF,
    /** Show a rule-of-thirds grid over the camera preview. */
    val cameraGrid: Boolean = false,
    /** Capture at the camera's full resolution (larger files) instead of a balanced size. */
    val cameraHd: Boolean = true,
    /** Apply [defaultFilter] to new pages automatically. Off: new pages start as [PageFilters.ORIGINAL]. */
    val autoEnhance: Boolean = true,
    /** Filter id (see [PageFilters]) new pages start with when [autoEnhance] is on. */
    val defaultFilter: String = PageFilters.ENHANCED,
    /**
     * Keep the app out of screenshots, screen recordings, casting and the
     * recent-apps preview. Off unless the user turns it on.
     */
    val blockScreenshots: Boolean = false,
)

/** App preferences. Stored in the encrypted key-value file like everything else. */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setAppLockEnabled(enabled: Boolean)

    suspend fun setLibraryLayout(layout: LibraryLayout)

    suspend fun setAutoCaptureEnabled(enabled: Boolean)

    suspend fun setWelcomeSeen()

    suspend fun setThemeMode(mode: ThemeMode)

    suspend fun setCameraFlash(mode: FlashMode)

    suspend fun setCameraGrid(enabled: Boolean)

    suspend fun setCameraHd(enabled: Boolean)

    suspend fun setAutoEnhance(enabled: Boolean)

    suspend fun setDefaultFilter(filterId: String)

    suspend fun setBlockScreenshots(enabled: Boolean)
}

internal class SettingsRepositoryImpl(
    private val values: EncryptedKeyValueStore,
    private val dispatchers: AppDispatchers,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = values.observeAll()
        .map { stored ->
            val defaults = AppSettings()
            AppSettings(
                appLockEnabled = stored[KEY_APP_LOCK] == TRUE,
                libraryLayout = if (stored[KEY_LAYOUT] == LibraryLayout.GRID.name) LibraryLayout.GRID else LibraryLayout.LIST,
                autoCaptureEnabled = stored[KEY_AUTO_CAPTURE] == TRUE,
                welcomeSeen = stored[KEY_WELCOME_SEEN] == TRUE,
                themeMode = enumOrDefault(stored[KEY_THEME], defaults.themeMode),
                cameraFlash = enumOrDefault(stored[KEY_FLASH], defaults.cameraFlash),
                cameraGrid = stored[KEY_GRID] == TRUE,
                cameraHd = stored[KEY_HD] != FALSE,
                autoEnhance = stored[KEY_AUTO_ENHANCE] != FALSE,
                defaultFilter = stored[KEY_DEFAULT_FILTER]?.takeIf { it.isNotBlank() } ?: defaults.defaultFilter,
                blockScreenshots = stored[KEY_BLOCK_SCREENSHOTS] == TRUE,
            )
        }
        .distinctUntilChanged()
        .flowOn(dispatchers.io)

    override suspend fun setAppLockEnabled(enabled: Boolean) = put(KEY_APP_LOCK, enabled.toString())

    override suspend fun setLibraryLayout(layout: LibraryLayout) = put(KEY_LAYOUT, layout.name)

    override suspend fun setAutoCaptureEnabled(enabled: Boolean) = put(KEY_AUTO_CAPTURE, enabled.toString())

    override suspend fun setWelcomeSeen() = put(KEY_WELCOME_SEEN, "true")

    override suspend fun setThemeMode(mode: ThemeMode) = put(KEY_THEME, mode.name)

    override suspend fun setCameraFlash(mode: FlashMode) = put(KEY_FLASH, mode.name)

    override suspend fun setCameraGrid(enabled: Boolean) = put(KEY_GRID, enabled.toString())

    override suspend fun setCameraHd(enabled: Boolean) = put(KEY_HD, enabled.toString())

    override suspend fun setAutoEnhance(enabled: Boolean) = put(KEY_AUTO_ENHANCE, enabled.toString())

    override suspend fun setDefaultFilter(filterId: String) = put(KEY_DEFAULT_FILTER, filterId)

    override suspend fun setBlockScreenshots(enabled: Boolean) = put(KEY_BLOCK_SCREENSHOTS, enabled.toString())

    private suspend fun put(key: String, value: String) = withContext(dispatchers.io) { values.put(key, value) }

    private inline fun <reified E : Enum<E>> enumOrDefault(stored: String?, default: E): E =
        stored?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private companion object {
        const val KEY_APP_LOCK = "settings.app_lock"
        const val KEY_LAYOUT = "settings.library_layout"

        // Only written when the user flips the switch, so reading it as "on only
        // when stored as true" keeps an explicit choice and makes it off for everyone else.
        const val KEY_AUTO_CAPTURE = "settings.auto_capture"
        const val KEY_WELCOME_SEEN = "settings.welcome_seen"
        const val KEY_THEME = "settings.theme"
        const val KEY_FLASH = "settings.camera_flash"
        const val KEY_GRID = "settings.camera_grid"
        const val KEY_HD = "settings.camera_hd"
        const val KEY_AUTO_ENHANCE = "settings.auto_enhance"
        const val KEY_DEFAULT_FILTER = "settings.default_filter"
        const val KEY_BLOCK_SCREENSHOTS = "settings.block_screenshots"
        const val TRUE = "true"
        const val FALSE = "false"
    }
}
