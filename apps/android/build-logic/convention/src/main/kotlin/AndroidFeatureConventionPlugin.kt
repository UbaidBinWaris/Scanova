import app.scanova.buildlogic.libs
import app.scanova.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Everything a feature module needs: Compose, Hilt, type-safe navigation and the
 * core modules a screen is allowed to use. Feature modules never depend on each
 * other (docs/ARCHITECTURE.md §9), so no feature project is added here.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("scanova.android.library")
            pluginManager.apply("scanova.android.compose")
            pluginManager.apply("scanova.android.hilt")
            pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

            dependencies {
                add("implementation", project(":core:common"))
                add("implementation", project(":core:designsystem"))
                add("implementation", project(":core:data"))

                add("implementation", libs.library("androidx-core-ktx"))
                add("implementation", libs.library("androidx-activity-compose"))
                add("implementation", libs.library("androidx-lifecycle-runtime-compose"))
                add("implementation", libs.library("androidx-lifecycle-viewmodel-compose"))
                add("implementation", libs.library("androidx-navigation-compose"))
                add("implementation", libs.library("androidx-hilt-lifecycle-viewmodel-compose"))
                add("implementation", libs.library("kotlinx-coroutines-android"))
                add("implementation", libs.library("kotlinx-serialization-json"))
            }
        }
    }
}
