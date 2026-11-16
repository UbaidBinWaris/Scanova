plugins {
    alias(libs.plugins.scanova.android.feature)
}

android {
    namespace = "app.scanova.feature.scan"
    // The page model on a real Android runtime (PageDetectionDeviceTest): emulator or phone.
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.opencv)
    // Hands the on-device page model its context at start-up (ScanModelsInitializer).
    implementation(libs.androidx.startup.runtime)
    // Text recognition (ML Kit), page orientation and document titles, shared with the background indexer.
    implementation(project(":core:ml"))

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
