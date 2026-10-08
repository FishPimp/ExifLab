import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.findByType

/** Compose compiler + common Compose dependencies. Apply after the application/library convention. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        extensions.findByType<ApplicationExtension>()?.buildFeatures?.compose = true
        extensions.findByType<LibraryExtension>()?.buildFeatures?.compose = true
        dependencies {
            val bom = libs.lib("androidx-compose-bom")
            add("implementation", platform(bom))
            add("androidTestImplementation", platform(bom))
            add("implementation", libs.lib("androidx-compose-ui"))
            add("implementation", libs.lib("androidx-compose-foundation"))
            add("implementation", libs.lib("androidx-compose-animation"))
            add("implementation", libs.lib("androidx-compose-material3"))
            add("implementation", libs.lib("androidx-compose-ui-tooling-preview"))
            add("debugImplementation", libs.lib("androidx-compose-ui-tooling"))
            add("debugImplementation", libs.lib("androidx-compose-ui-test-manifest"))
            add("testImplementation", libs.lib("androidx-compose-ui-test-junit4"))
            add("androidTestImplementation", libs.lib("androidx-compose-ui-test-junit4"))
        }
    }
}
