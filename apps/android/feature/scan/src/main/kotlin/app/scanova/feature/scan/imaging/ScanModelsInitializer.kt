package app.scanova.feature.scan.imaging

import android.content.Context
import androidx.startup.Initializer

/**
 * Hands the page models their application context at start-up
 * (androidx.startup, declared in this module's manifest), so every screen can
 * use them without passing a Context along. Loads nothing: each model is read
 * from the app's assets on first use.
 */
class ScanModelsInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        AiPageDetector.install(context)
        PageFlattener.install(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
