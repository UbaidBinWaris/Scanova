plugins {
    alias(libs.plugins.scanova.android.feature)
}

android {
    namespace = "app.scanova.feature.scan"
}

dependencies {
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.opencv)
    // Text recognition (ML Kit), page orientation and document titles, shared with the background indexer.
    implementation(project(":core:ml"))
}
