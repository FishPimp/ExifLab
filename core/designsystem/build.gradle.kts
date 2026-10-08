plugins {
    id("exiflab.android.library")
    id("exiflab.android.compose")
}

android {
    namespace = "io.github.fishpimp.exiflab.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material3.adaptive)
    api(libs.androidx.compose.material3.navigation.suite)
    api(libs.androidx.graphics.shapes)
    implementation(libs.androidx.core.ktx)
}
