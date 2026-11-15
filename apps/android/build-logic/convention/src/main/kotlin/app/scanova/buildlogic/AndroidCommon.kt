package app.scanova.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * Settings shared by the application module and every library module: SDK levels,
 * Java level, lint policy and the baseline test dependencies.
 */
internal fun Project.configureAndroidCommon(extension: CommonExtension) {
    extension.apply {
        compileSdk = libs.intVersion("compileSdk")

        defaultConfig.minSdk = libs.intVersion("minSdk")

        compileOptions.sourceCompatibility = JavaVersion.VERSION_17
        compileOptions.targetCompatibility = JavaVersion.VERSION_17

        lint.abortOnError = true
        lint.checkReleaseBuilds = false
        // The catalog pins exact versions on purpose; newer-version hints are noise.
        lint.disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")

        testOptions.unitTests.isReturnDefaultValues = true

        packaging.resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
        )
    }

    tasks.withType<Test>().configureEach {
        // Unit tests share the machine with the build; keep each test JVM small.
        maxHeapSize = "768m"
        maxParallelForks = 1
    }

    dependencies {
        add("testImplementation", libs.library("junit"))
        add("testImplementation", libs.library("kotlinx-coroutines-test"))
    }
}
