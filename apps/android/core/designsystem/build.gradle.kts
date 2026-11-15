plugins {
    alias(libs.plugins.scanova.android.library)
    alias(libs.plugins.scanova.android.compose)
}

android {
    namespace = "app.scanova.core.designsystem"
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.androidx.core.ktx)
}
