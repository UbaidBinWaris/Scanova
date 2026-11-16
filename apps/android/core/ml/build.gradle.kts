plugins {
    alias(libs.plugins.scanova.android.library)
    alias(libs.plugins.scanova.android.hilt)
}

android {
    namespace = "app.scanova.core.ml"
}

// On-device "intelligence" without a model of its own: ML Kit text recognition
// (bundled Latin model, offline) and transparent rules on the recognised text.
// Everything here runs on the phone; nothing is sent anywhere.
dependencies {
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.kotlinx.coroutines.android)
}
