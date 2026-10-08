plugins {
    id("exiflab.android.library")
    id("exiflab.android.compose")
}

android {
    namespace = "io.github.fishpimp.exiflab.core.ui"
}

dependencies {
    api(project(":core:designsystem"))
    api("io.github.fishpimp.exiflab.engine:model")
    implementation(libs.coil.compose)
    implementation(libs.androidx.core.ktx)
}
