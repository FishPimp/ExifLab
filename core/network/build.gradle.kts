plugins {
    id("exiflab.android.library")
    id("exiflab.android.compose")
    id("exiflab.android.hilt")
}

android {
    namespace = "io.github.fishpimp.exiflab.core.network"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(libs.okhttp)
    api(libs.maplibre.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
}
