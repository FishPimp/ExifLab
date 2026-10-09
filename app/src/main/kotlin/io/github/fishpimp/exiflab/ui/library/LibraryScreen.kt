package io.github.fishpimp.exiflab.ui.library

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.folders.GrantedFolder
import io.github.fishpimp.exiflab.data.folders.LibraryFolder
import io.github.fishpimp.exiflab.data.folders.rootDocumentUri
import io.github.fishpimp.exiflab.designsystem.component.EmptyState
import io.github.fishpimp.exiflab.ui.components.ScreenScaffold
import io.github.fishpimp.exiflab.ui.navigation.Route
import kotlinx.coroutines.launch

/** Granted folders: add, browse, re-grant lost access, remove. */
@Composable
fun LibraryScreen(
    onOpenFolder: (Route.Folder) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    var regrantingTreeUri by rememberSaveable { mutableStateOf<String?>(null) }

    val addFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        viewModel.addFolder(uri)
    }
    val regrantFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        regrantingTreeUri?.let { previous -> viewModel.regrant(previous, uri) }
        regrantingTreeUri = null
    }

    fun showError() {
        scope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.library_add_failed)) }
    }

    fun launchFolderPicker(start: () -> Unit) {
        try {
            start()
        } catch (_: ActivityNotFoundException) {
            showError()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.errors.collect { showError() }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    LibraryContent(
        folders = folders,
        onAddFolder = { launchFolderPicker { addFolder.launch(null) } },
        onOpenFolder = { item ->
            onOpenFolder(Route.Folder(item.folder.treeUri, item.folder.rootDocumentUri, item.folder.displayName))
        },
        onRegrant = { folder ->
            regrantingTreeUri = folder.treeUri
            launchFolderPicker { regrantFolder.launch(folder.treeUri.toUri()) }
        },
        onRemove = viewModel::removeFolder,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

/**
 * Stateless Library layout, rendered directly by screenshot tests.
 *
 * @param folders granted folders, or null while they load.
 */
@Composable
fun LibraryContent(
    folders: List<LibraryFolder>?,
    onAddFolder: () -> Unit,
    onOpenFolder: (LibraryFolder) -> Unit,
    onRegrant: (GrantedFolder) -> Unit,
    onRemove: (GrantedFolder) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var removingTreeUri by rememberSaveable { mutableStateOf<String?>(null) }
    ScreenScaffold(
        title = stringResource(R.string.nav_library),
        modifier = modifier,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddFolder,
                icon = { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null) },
                text = { Text(stringResource(R.string.library_add_folder)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                folders == null -> Unit
                folders.isEmpty() -> Box(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Rounded.PhotoLibrary,
                        shape = MaterialShapes.Sunny,
                        title = stringResource(R.string.library_empty_title),
                        body = stringResource(R.string.library_empty_body),
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                    // Bottom padding keeps the last card clear of the floating action button.
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(folders, key = { it.folder.treeUri }) { item ->
                        LibraryFolderCard(
                            item = item,
                            onOpen = { onOpenFolder(item) },
                            onRegrant = { onRegrant(item.folder) },
                            onRemove = { removingTreeUri = item.folder.treeUri },
                        )
                    }
                }
            }
        }
    }
    val removing = folders?.firstOrNull { it.folder.treeUri == removingTreeUri }?.folder
    if (removing != null) {
        RemoveFolderDialog(
            folder = removing,
            onConfirm = {
                removingTreeUri = null
                onRemove(removing)
            },
            onDismiss = { removingTreeUri = null },
        )
    }
}

@Composable
private fun RemoveFolderDialog(folder: GrantedFolder, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.FolderOff, contentDescription = null) },
        title = { Text(stringResource(R.string.library_remove_title, folder.displayName)) },
        text = { Text(stringResource(R.string.library_remove_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_remove)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
