plugins {
    id("exiflab.android.application")
    id("exiflab.android.compose")
    id("exiflab.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.fishpimp.exiflab"

    defaultConfig {
        applicationId = "io.github.fishpimp.exiflab"
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        ndk {
            // Phones (arm64/armv7) and the x86_64 emulator used by CI.
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        create("personal") {
            storeFile = file("signing/exiflab-personal.jks")
            storePassword = "exiflab-personal"
            keyAlias = "exiflab"
            keyPassword = "exiflab-personal"
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("personal")
        }
        getByName("release") {
            // Not minified: no reflection surprises from metadata-extractor/XMP Core, and the APK is for personal use.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":feature:home"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material3.navigation.suite)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.serialization.json)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
