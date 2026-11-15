plugins {
    alias(libs.plugins.scanova.android.library)
    alias(libs.plugins.scanova.android.hilt)
}

android {
    namespace = "app.scanova.core.database"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:security"))

    api(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)
}
