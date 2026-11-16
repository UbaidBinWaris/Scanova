plugins {
    alias(libs.plugins.scanova.android.library)
    alias(libs.plugins.scanova.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.scanova.core.data"
}

dependencies {
    api(project(":core:common"))
    // On-device text recognition and document rules (type, title). `api`: screens show the type.
    api(project(":core:ml"))
    implementation(project(":core:security"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)

    // Parses the PDFs PdfWriter writes, to prove they are valid. Tests only.
    testImplementation(libs.pdfbox)
}
