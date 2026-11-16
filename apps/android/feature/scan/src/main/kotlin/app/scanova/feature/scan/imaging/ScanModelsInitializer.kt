package app.scanova.feature.scan.imaging

import android.content.Context
import androidx.startup.Initializer

/**
 * Hands the page model its application context at start-up (androidx.startup,
 * declared in this module's manifest), so every detector in the app can use it
 * without each screen passing a Context along. Loads nothing: the model is read
 * from the app's assets on first use.
 */
class ScanModelsInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        AiPageDetector.install(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
