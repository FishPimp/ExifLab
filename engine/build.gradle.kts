plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

subprojects {
    group = "io.github.fishpimp.exiflab.engine"
    version = "1.0"
}
