// Standalone build used only by the apidump CI job to download sources jars of AndroidX libraries.
plugins { base }

repositories {
    google()
    mavenCentral()
}

val sourcesOnly: Configuration by configurations.creating {
    isTransitive = false
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
        attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.SOURCES))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}

dependencies {
    val v = mapOf(
        "androidx.compose.material3:material3" to "1.4.0",
        "androidx.compose.material3:material3-adaptive-navigation-suite" to "1.4.0",
        "androidx.compose.material3.adaptive:adaptive-layout" to "1.3.0",
        "androidx.compose.material3.adaptive:adaptive-navigation" to "1.3.0",
        "androidx.compose.material3.adaptive:adaptive" to "1.3.0",
        "androidx.navigation:navigation-compose" to "2.10.2",
        "androidx.navigation:navigation-common" to "2.10.2",
        "androidx.navigation:navigation-runtime" to "2.10.2",
        "androidx.activity:activity-compose" to "1.13.0",
        "androidx.activity:activity" to "1.13.0",
        "androidx.lifecycle:lifecycle-runtime-compose" to "2.11.0",
        "androidx.hilt:hilt-navigation-compose" to "1.4.0",
        "androidx.hilt:hilt-lifecycle-viewmodel-compose" to "1.4.0",
        "androidx.compose.foundation:foundation" to "1.12.1",
        "androidx.compose.ui:ui" to "1.12.1",
        "io.coil-kt.coil3:coil-compose-core" to "3.6.3",
        "io.coil-kt.coil3:coil-core" to "3.6.3",
        "androidx.work:work-runtime" to "2.12.0",
        "androidx.room:room-common" to "2.8.5",
    )
    v.forEach { (m, ver) -> sourcesOnly("$m:$ver") }
}

tasks.register<Copy>("dumpSources") {
    from(sourcesOnly)
    into(layout.buildDirectory.dir("sources"))
}
