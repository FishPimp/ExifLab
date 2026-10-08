plugins {
    kotlin("jvm") version "2.4.21"
    application
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation("com.drewnoakes:metadata-extractor:2.21.0")
    implementation("com.adobe.xmp:xmpcore:6.1.11")
    implementation("org.apache.commons:commons-imaging:1.0.0-alpha6")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

application { mainClass.set("exiflab.spike.MainKt") }

// Platform android.media.ExifInterface (same code base as androidx.exifinterface) under Robolectric.
// androidx.test artifacts live on Google Maven, which is not reachable here; they are excluded and
// Robolectric falls back to its own instrumentation registry.
val robolectricAndroidAll = "org.robolectric:android-all-instrumented:16-robolectric-13921718-i7"
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17") {
        exclude(group = "androidx.test")
        exclude(group = "androidx.test.espresso")
    }
    testCompileOnly(robolectricAndroidAll)
    testRuntimeOnly(robolectricAndroidAll)
}
tasks.test {
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    systemProperty("spike.samples", System.getProperty("spike.samples") ?: "samples")
    systemProperty("spike.out", System.getProperty("spike.out") ?: layout.buildDirectory.dir("spike-out").get().asFile.path)
    systemProperty("spike.results", System.getProperty("spike.results") ?: file("results").path)
    environment("EXIFTOOL", System.getenv("EXIFTOOL") ?: "exiftool")
    testLogging { showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
