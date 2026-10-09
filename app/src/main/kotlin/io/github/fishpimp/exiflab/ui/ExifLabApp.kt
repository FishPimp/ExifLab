package io.github.fishpimp.exiflab.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.fishpimp.exiflab.ui.history.HistoryScreen
import io.github.fishpimp.exiflab.ui.home.HomeScreen
import io.github.fishpimp.exiflab.ui.library.LibraryScreen
import io.github.fishpimp.exiflab.ui.navigation.Route
import io.github.fishpimp.exiflab.ui.navigation.TopLevelDestination
import io.github.fishpimp.exiflab.ui.privacy.LicensesScreen
import io.github.fishpimp.exiflab.ui.privacy.PrivacyScreen
import io.github.fishpimp.exiflab.ui.settings.SettingsScreen

/**
 * App shell: adaptive navigation (bar on phones, rail on larger windows) around a
 * Navigation 3 display. Every top-level destination keeps its own back stack and state.
 */
@Composable
fun ExifLabApp() {
    var currentTab by rememberSaveable { mutableStateOf(TopLevelDestination.Home) }
    val backStacks: Map<TopLevelDestination, NavBackStack<NavKey>> =
        TopLevelDestination.entries.associateWith { rememberNavBackStack(it.root) }

    fun navigate(route: Route) {
        backStacks.getValue(currentTab).add(route)
    }

    fun back() {
        val stack = backStacks.getValue(currentTab)
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else currentTab = TopLevelDestination.Home
    }

    val provider = entryProvider<NavKey> {
        entry<Route.Home> {
            HomeScreen(onOpenPrivacy = { navigate(Route.Privacy) })
        }
        entry<Route.Library> { LibraryScreen() }
        entry<Route.History> { HistoryScreen() }
        entry<Route.Settings> {
            SettingsScreen(
                onOpenPrivacy = { navigate(Route.Privacy) },
                onOpenLicenses = { navigate(Route.Licenses) },
            )
        }
        entry<Route.Privacy> { PrivacyScreen(onBack = ::back) }
        entry<Route.Licenses> { LicensesScreen(onBack = ::back) }
    }

    val entriesByTab = TopLevelDestination.entries.associateWith { destination ->
        rememberDecoratedNavEntries(
            backStack = backStacks.getValue(destination),
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = provider,
        )
    }

    BackHandler(enabled = currentTab != TopLevelDestination.Home && backStacks.getValue(currentTab).size == 1) {
        currentTab = TopLevelDestination.Home
    }

    NavigationSuiteScaffold(
        navigationItems = {
            TopLevelDestination.entries.forEach { destination ->
                val selected = destination == currentTab
                NavigationSuiteItem(
                    selected = selected,
                    onClick = {
                        if (selected) {
                            // Re-selecting a tab returns to its root.
                            val stack = backStacks.getValue(destination)
                            while (stack.size > 1) stack.removeAt(stack.lastIndex)
                        } else {
                            currentTab = destination
                        }
                    },
                    icon = {
                        Icon(
                            if (selected) destination.selectedIcon else destination.unselectedIcon,
                            contentDescription = null,
                        )
                    },
                    label = { Text(stringResource(destination.label)) },
                )
            }
        },
    ) {
        NavDisplay(
            entries = entriesByTab.getValue(currentTab),
            onBack = ::back,
        )
    }
}
