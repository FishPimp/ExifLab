// Standalone pure-JVM build for the metadata engine. It is included by the root build and can also be
// built on its own (`./gradlew -p engine test`), which works without Google Maven.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "engine"
include(":model", ":metadata")
