plugins {
    alias(libs.plugins.scanova.android.library)
    alias(libs.plugins.scanova.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.scanova.core.security"
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.tink.android)
    implementation(libs.kotlinx.serialization.json)
}
