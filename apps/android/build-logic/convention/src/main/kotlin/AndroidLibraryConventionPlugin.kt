import app.scanova.buildlogic.configureAndroidCommon
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Applies the Android library plugin with Scanova's shared defaults. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")

            extensions.configure<LibraryExtension> {
                configureAndroidCommon(this)
                // Each library keeps its own resources private by prefix, so two
                // modules can never define the same string or drawable name.
                resourcePrefix = path.split(":").filter { it.isNotEmpty() }.joinToString("_") + "_"
            }
        }
    }
}
