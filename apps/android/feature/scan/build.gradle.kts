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
    implementation(libs.mlkit.text.recognition)

    // Parses the PDFs this module writes, to prove they are valid. Tests only.
    testImplementation(libs.pdfbox)
}
