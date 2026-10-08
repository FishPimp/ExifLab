plugins {
    id("exiflab.android.library")
    id("exiflab.android.hilt")
    alias(libs.plugins.room)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.fishpimp.exiflab.core.data"
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api("io.github.fishpimp.exiflab.engine:metadata")
    api("io.github.fishpimp.exiflab.engine:model")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
}
