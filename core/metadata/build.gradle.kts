plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    // Parsing engines. Nothing from them is exposed: the public API is the model in `metadata.model`.
    implementation(libs.metadata.extractor)
    implementation(libs.xmpcore)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test {
    // The opt-in real-world sample test (RealWorldSamplesTest) reads EXIFLAB_SAMPLES_DIR; rerun when it changes.
    inputs.property("exiflabSamplesDir", providers.environmentVariable("EXIFLAB_SAMPLES_DIR").orElse(""))
    // Optional output directory for the files the sample writer test produces.
    inputs.property("exiflabSamplesOut", providers.environmentVariable("EXIFLAB_SAMPLES_OUT").orElse(""))
    inputs.property("exiflabWriteTestOut", providers.environmentVariable("EXIFLAB_WRITE_TEST_OUT").orElse(""))
}
