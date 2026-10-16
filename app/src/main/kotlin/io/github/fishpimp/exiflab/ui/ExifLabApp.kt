package io.github.fishpimp.exiflab.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass
import io.github.fishpimp.exiflab.appGraph
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.ui.batch.BatchEditScreen
import io.github.fishpimp.exiflab.ui.batch.BatchRunScreen
import io.github.fishpimp.exiflab.ui.edit.EditScreen
import io.github.fishpimp.exiflab.ui.folder.FolderScreen
import io.github.fishpimp.exiflab.ui.history.HistoryDetailScreen
import io.github.fishpimp.exiflab.ui.history.HistoryScreen
import io.github.fishpimp.exiflab.ui.home.HomeScreen
import io.github.fishpimp.exiflab.ui.library.LibraryScreen
import io.github.fishpimp.exiflab.ui.map.MapViewScreen
import io.github.fishpimp.exiflab.ui.navigation.ExternalOpen
import io.github.fishpimp.exiflab.ui.navigation.Route
import io.github.fishpimp.exiflab.ui.navigation.TopLevelDestination
import io.github.fishpimp.exiflab.ui.navigation.routeFor
import io.github.fishpimp.exiflab.ui.photo.PhotoScreen
import io.github.fishpimp.exiflab.ui.privacy.LicensesScreen
import io.github.fishpimp.exiflab.ui.privacy.PrivacyScreen
import io.github.fishpimp.exiflab.ui.selection.SelectionScreen
import io.github.fishpimp.exiflab.ui.settings.SettingsScreen

/**
 * App shell: adaptive navigation (bar on phones, rail on larger windows) around a
 * Navigation 3 display. Every top-level destination keeps its own back stack and state.
 *
 * @param externalOpen shared photos to show. One present on the first composition becomes the
 *   initial screen, so a share opens directly on the photo; later ones are pushed on Home.
 * @param onExternalOpenHandled called once [externalOpen] is on the back stack.
 * @param onExitSharedPhotos called on back from shared photos, to return to the sharing app.
 * @param openBatchJob a batch job to show (its notification was tapped): the tab already showing
 *   its run screen, else pushed on Home.
 * @param onBatchJobOpened called once [openBatchJob] is on the back stack.
 */
@Composable
fun ExifLabApp(
    externalOpen: ExternalOpen? = null,
    onExternalOpenHandled: (ExternalOpen) -> Unit = {},
    onExitSharedPhotos: () -> Unit = {},
    openBatchJob: String? = null,
    onBatchJobOpened: (String) -> Unit = {},
) {
    var currentTab by rememberSaveable { mutableStateOf(TopLevelDestination.Home) }
    // Only the first composition's request seeds the stacks; restored stacks ignore it.
    val initialOpen = remember { externalOpen }
    val backStacks: Map<TopLevelDestination, NavBackStack<NavKey>> =
        TopLevelDestination.entries.associateWith { destination ->
            val initialEntries: Array<NavKey> = if (destination == TopLevelDestination.Home && initialOpen != null) {
                arrayOf(destination.root, routeFor(initialOpen.refs))
            } else {
                arrayOf(destination.root)
            }
            rememberNavBackStack(*initialEntries)
        }
    // Depth of the Home stack whose top entry holds shared photos; back from it leaves the app.
    var sharedEntryDepth by rememberSaveable { mutableStateOf(initialOpen?.let { 2 }) }
    var handledOpenId by rememberSaveable { mutableStateOf(initialOpen?.id) }

    // The edit open beside History's list on expanded windows. Unfolding turns edits opened as
    // their own screens into this selection; folding opens it as a screen again (HistoryScreen).
    var historySelection by rememberSaveable { mutableStateOf<String?>(null) }
    val expanded = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val historyStack = backStacks.getValue(TopLevelDestination.History)
    LaunchedEffect(expanded, historyStack.lastOrNull()) {
        val details = historyStack.drop(1)
        if (expanded && details.isNotEmpty() && details.all { it is Route.HistoryDetail }) {
            historySelection = (details.last() as Route.HistoryDetail).recordId
            while (historyStack.size > 1) historyStack.removeAt(historyStack.lastIndex)
        }
    }

    LaunchedEffect(externalOpen) {
        val open = externalOpen ?: return@LaunchedEffect
        if (open.id != handledOpenId) {
            val home = backStacks.getValue(TopLevelDestination.Home)
            home.add(routeFor(open.refs))
            currentTab = TopLevelDestination.Home
            sharedEntryDepth = home.size
            handledOpenId = open.id
        }
        onExternalOpenHandled(open)
    }

    fun navigate(route: Route) {
        backStacks.getValue(currentTab).add(route)
    }

    /** Replaces the screen on top, e.g. the batch editor with the job it started. */
    fun replaceTop(route: Route) {
        val stack = backStacks.getValue(currentTab)
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        stack.add(route)
    }

    LaunchedEffect(openBatchJob) {
        val jobId = openBatchJob ?: return@LaunchedEffect
        // A batch started from a folder already shows its run screen on that tab; go there.
        val showing = backStacks.entries.firstOrNull { (_, stack) -> (stack.lastOrNull() as? Route.BatchRun)?.jobId == jobId }?.key
        if (showing != null) {
            currentTab = showing
        } else {
            backStacks.getValue(TopLevelDestination.Home).add(Route.BatchRun(jobId))
            currentTab = TopLevelDestination.Home
        }
        onBatchJobOpened(jobId)
    }

    fun openPhoto(ref: PhotoRef) = navigate(Route.Photo(ref))

    fun back() {
        val stack = backStacks.getValue(currentTab)
        when {
            currentTab == TopLevelDestination.Home && stack.size == sharedEntryDepth -> {
                sharedEntryDepth = null
                onExitSharedPhotos()
            }
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
            else -> currentTab = TopLevelDestination.Home
        }
    }

    val provider = entryProvider<NavKey> {
        entry<Route.Home> {
            HomeScreen(
                onOpenPhotos = { refs -> navigate(routeFor(refs)) },
                onBrowseFolders = { currentTab = TopLevelDestination.Library },
                onOpenPrivacy = { navigate(Route.Privacy) },
                onOpenBatchJob = { navigate(Route.BatchRun(it)) },
            )
        }
        entry<Route.Library> { LibraryScreen(onOpenFolder = ::navigate) }
        entry<Route.History> {
            HistoryScreen(
                onOpenRecord = { navigate(Route.HistoryDetail(it)) },
                onOpenPhoto = ::openPhoto,
                selectedId = historySelection,
                onSelect = { historySelection = it },
            )
        }
        entry<Route.HistoryDetail> { route ->
            HistoryDetailScreen(
                recordId = route.recordId,
                onBack = ::back,
                onOpenPhoto = ::openPhoto,
                onOpenRecord = { navigate(Route.HistoryDetail(it)) },
            )
        }
        entry<Route.Settings> {
            SettingsScreen(
                onOpenPrivacy = { navigate(Route.Privacy) },
                onOpenLicenses = { navigate(Route.Licenses) },
            )
        }
        entry<Route.Privacy> { PrivacyScreen(onBack = ::back) }
        entry<Route.Licenses> { LicensesScreen(onBack = ::back) }
        entry<Route.Folder> { route ->
            FolderScreen(
                route = route,
                onBack = ::back,
                onOpenFolder = ::navigate,
                onOpenPhoto = ::openPhoto,
                onBatchEdit = ::navigate,
                onOpenLibrary = {
                    // Access is granted again from the Library's folder cards.
                    val stack = backStacks.getValue(TopLevelDestination.Library)
                    while (stack.size > 1) stack.removeAt(stack.lastIndex)
                    currentTab = TopLevelDestination.Library
                },
            )
        }
        entry<Route.Selection> { route ->
            SelectionScreen(refs = route.refs, onBack = ::back, onOpenPhoto = ::openPhoto, onBatchEdit = ::navigate)
        }
        entry<Route.BatchEdit> { route ->
            BatchEditScreen(
                jobId = route.jobId,
                kind = route.kind,
                onBack = ::back,
                // The editor's page is replaced by the running job, so back returns to the photos.
                onStarted = { replaceTop(Route.BatchRun(it)) },
            )
        }
        entry<Route.BatchRun> { route ->
            BatchRunScreen(jobId = route.jobId, onBack = ::back, onDone = ::back, onOpenPhoto = ::openPhoto)
        }
        entry<Route.MapView> { route -> MapViewScreen(route = route, onBack = ::back) }
        entry<Route.Photo> { route ->
            RecordRecentPhoto(route.ref)
            PhotoScreen(
                ref = route.ref,
                onBack = ::back,
                onOpenPhoto = ::openPhoto,
                onEdit = { navigate(Route.Edit(it)) },
            )
        }
        entry<Route.Edit> { route ->
            EditScreen(
                ref = route.ref,
                onBack = ::back,
                onFinished = { copy ->
                    // Back to the photo, which reloads; a new copy opens on top of it.
                    back()
                    copy?.let(::openPhoto)
                },
            )
        }
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
                            if (destination == TopLevelDestination.Home) sharedEntryDepth = null
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
        // Back from shared photos returns to the sharing app, so Home under them is left out:
        // with nothing behind, predictive back previews that app instead of a screen never reached.
        val entries = entriesByTab.getValue(currentTab).let { entries ->
            val depth = sharedEntryDepth
            if (currentTab == TopLevelDestination.Home && depth != null && entries.size >= depth) entries.drop(depth - 1) else entries
        }
        NavDisplay(
            entries = entries,
            onBack = ::back,
        )
    }
}

/** Adds [ref] to the recent photos when its screen is shown. */
@Composable
private fun RecordRecentPhoto(ref: PhotoRef) {
    val recents = LocalContext.current.appGraph.recentPhotosRepository
    LaunchedEffect(ref) { recents.record(ref) }
}
