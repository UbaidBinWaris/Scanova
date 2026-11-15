plugins {
    alias(libs.plugins.scanova.android.feature)
}

android {
    namespace = "app.scanova.feature.pairing"
}

dependencies {
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
}
