plugins {
    alias(libs.plugins.scanova.android.library)
    alias(libs.plugins.scanova.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.scanova.core.network"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:security"))

    api(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
}
