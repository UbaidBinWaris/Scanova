plugins {
    alias(libs.plugins.scanova.android.feature)
}

android {
    namespace = "app.scanova.feature.settings"
}

dependencies {
    implementation(libs.androidx.biometric)
}
