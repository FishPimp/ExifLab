package io.github.fishpimp.exiflab.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import io.github.fishpimp.exiflab.R
import kotlinx.serialization.Serializable

/** Every screen the app can show. Keys are serializable so back stacks survive process death. */
@Serializable
sealed interface Route : NavKey {
    @Serializable data object Home : Route
    @Serializable data object Library : Route
    @Serializable data object History : Route
    @Serializable data object Settings : Route
    @Serializable data object Privacy : Route
    @Serializable data object Licenses : Route
}

/** Destinations shown in the navigation bar or rail. Each keeps its own back stack. */
enum class TopLevelDestination(
    val root: Route,
    @StringRes val label: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Home(Route.Home, R.string.nav_home, Icons.Rounded.Home, Icons.Outlined.Home),
    Library(Route.Library, R.string.nav_library, Icons.Rounded.PhotoLibrary, Icons.Outlined.PhotoLibrary),
    History(Route.History, R.string.nav_history, Icons.Rounded.History, Icons.Outlined.History),
    Settings(Route.Settings, R.string.nav_settings, Icons.Rounded.Settings, Icons.Outlined.Settings),
}
