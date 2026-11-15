plugins {
    alias(libs.plugins.scanova.android.application)
    alias(libs.plugins.scanova.android.compose)
    alias(libs.plugins.scanova.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Release builds come from .github/workflows/android-release.yml, which sets
// these. Without them a local release build is signed with the debug key and
// cannot be published (publish-apk.sh refuses another signing key).
val appVersionCode = providers.environmentVariable("APP_VERSION_CODE").orNull?.toIntOrNull() ?: 1
val appVersionName = providers.environmentVariable("APP_VERSION_NAME").orNull ?: "0.1.0"
val releaseStoreFile = providers.environmentVariable("RELEASE_STORE_FILE").orNull

android {
    namespace = "app.scanova.android"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "app.scanova.android"
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.environmentVariable("RELEASE_STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // 64-bit ARM phones only: OpenCV for four ABIs would quadruple the download.
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }
}
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:security"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))

    implementation(project(":feature:pairing"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:scan"))
    implementation(project(":feature:documents"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:tools"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
