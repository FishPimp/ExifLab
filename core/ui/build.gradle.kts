plugins {
    id("exiflab.android.library")
    id("exiflab.android.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.fishpimp.exiflab.core.ui"
}

dependencies {
    api(project(":core:designsystem"))
    api(project(":core:data"))
    api(libs.coil.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.serialization.json)
}
