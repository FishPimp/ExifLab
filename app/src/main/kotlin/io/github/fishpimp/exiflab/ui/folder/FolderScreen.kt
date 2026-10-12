package io.github.fishpimp.exiflab.ui.folder

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.batch.BatchKind
import io.github.fishpimp.exiflab.data.folders.FolderListing
import io.github.fishpimp.exiflab.data.folders.Subfolder
import io.github.fishpimp.exiflab.data.photos.PhotoAccessError
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.designsystem.component.EmptyState
import io.github.fishpimp.exiflab.ui.components.PhotoGridCells
import io.github.fishpimp.exiflab.ui.components.ScreenScaffold
import io.github.fishpimp.exiflab.ui.components.isLargeFontScale
import io.github.fishpimp.exiflab.ui.navigation.Route
import io.github.fishpimp.exiflab.ui.selection.BatchActionsToolbar
import io.github.fishpimp.exiflab.ui.selection.PhotoSelection
import io.github.fishpimp.exiflab.ui.selection.SelectablePhotoTile
import io.github.fishpimp.exiflab.ui.selection.SelectionActions
import io.github.fishpimp.exiflab.ui.selection.SelectionBarActions
import io.github.fishpimp.exiflab.ui.selection.SelectionBottomPadding
import io.github.fishpimp.exiflab.ui.selection.dragToSelect
import io.github.fishpimp.exiflab.ui.selection.selectionTitle
import io.github.fishpimp.exiflab.ui.shareclean.ShareCleanSheetHost
import io.github.fishpimp.exiflab.ui.shareclean.shareCleanViewModel

/**
 * Browses one folder of a granted tree: subfolders first, then photos newest first. Long press
 * (or "Select") starts multi-select, with batch actions in a floating toolbar.
 */
@Composable
fun FolderScreen(
    route: Route.Folder,
    onBack: () -> Unit,
    onOpenFolder: (Route.Folder) -> Unit,
    onOpenPhoto: (PhotoRef) -> Unit,
    onBatchEdit: (Route.BatchEdit) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FolderViewModel = viewModel(factory = FolderViewModel.factory(route)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val currentOnBatchEdit by rememberUpdatedState(onBatchEdit)
    LaunchedEffect(viewModel) { viewModel.batchEditRequests.collect { currentOnBatchEdit(it) } }
    BackHandler(enabled = selection.active) { viewModel.selectionActions.onExit() }
    val shareClean = shareCleanViewModel()
    FolderContent(
        title = route.title,
        state = state,
        onBack = onBack,
        onOpenSubfolder = { onOpenFolder(Route.Folder(route.folderTreeUri, it.documentUri, it.name)) },
        onOpenPhoto = onOpenPhoto,
        selection = selection,
        selectionActions = viewModel.selectionActions,
        onBatchAction = viewModel::startBatch,
        onShareClean = {
            val photos = (state as? FolderUiState.Loaded)?.listing?.photos.orEmpty()
            shareClean.open(selection.selectedIn(photos))
        },
        modifier = modifier,
    )
    ShareCleanSheetHost(shareClean)
}

/** Stateless folder layout, rendered directly by screenshot tests. */
@Composable
fun FolderContent(
    title: String,
    state: FolderUiState,
    onBack: () -> Unit,
    onOpenSubfolder: (Subfolder) -> Unit,
    onOpenPhoto: (PhotoRef) -> Unit,
    modifier: Modifier = Modifier,
    selection: PhotoSelection = PhotoSelection(),
    selectionActions: SelectionActions = SelectionActions(),
    onBatchAction: (BatchKind) -> Unit = {},
    onShareClean: (() -> Unit)? = null,
) {
    val listing = (state as? FolderUiState.Loaded)?.listing
    val hasPhotos = listing?.photos?.isNotEmpty() == true
    ScreenScaffold(
        title = if (selection.active) selectionTitle(selection) else title,
        subtitle = if (selection.active) title else listing?.let { folderSummary(it) },
        onBack = if (selection.active) selectionActions.onExit else onBack,
        navigationIcon = if (selection.active) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
        navigationLabel = if (selection.active) stringResource(R.string.selection_exit) else null,
        actions = {
            if (hasPhotos) {
                SelectionBarActions(selection, selectionActions, total = listing.photos.size, enterSelectsAll = false)
            }
        },
        modifier = modifier,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                state is FolderUiState.Failed -> CenteredMessage { FolderError(state.error) }
                listing == null || (listing.isEmpty && listing.isLoading) -> FolderLoading(Modifier.align(Alignment.Center))
                listing.isEmpty -> CenteredMessage {
                    EmptyState(
                        icon = Icons.Rounded.HideImage,
                        shape = MaterialShapes.Clover8Leaf,
                        title = stringResource(R.string.folder_empty_title),
                        body = stringResource(R.string.folder_empty_body),
                    )
                }
                else -> FolderGrid(listing, onOpenSubfolder, onOpenPhoto, selection, selectionActions)
            }
            BatchActionsToolbar(
                visible = selection.active,
                enabled = selection.count > 0,
                onAction = onBatchAction,
                modifier = Modifier.align(Alignment.BottomCenter),
                onShareClean = onShareClean,
            )
        }
    }
}

@Composable
private fun FolderGrid(
    listing: FolderListing,
    onOpenSubfolder: (Subfolder) -> Unit,
    onOpenPhoto: (PhotoRef) -> Unit,
    selection: PhotoSelection,
    actions: SelectionActions,
) {
    val showHeaders = listing.subfolders.isNotEmpty() && listing.photos.isNotEmpty()
    val wideFolders = isLargeFontScale()
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = PhotoGridCells,
        state = gridState,
        modifier = Modifier
            .widthIn(max = 1200.dp)
            .fillMaxSize()
            .dragToSelect(gridState, listing.photos, selection, actions),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = if (selection.active) SelectionBottomPadding else 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showHeaders) header("header-folders", R.string.folder_section_folders)
        items(
            listing.subfolders,
            key = { it.documentUri },
            span = { GridItemSpan(if (wideFolders) maxLineSpan else 1) },
            contentType = { "folder" },
        ) { folder ->
            FolderTile(name = folder.name, onClick = { onOpenSubfolder(folder) }, wide = wideFolders)
        }
        if (showHeaders) header("header-photos", R.string.folder_section_photos)
        items(listing.photos, key = { it.uri }, contentType = { "photo" }) { ref ->
            SelectablePhotoTile(
                ref = ref,
                selectionActive = selection.active,
                selected = selection.isSelected(ref.uri),
                onOpen = { onOpenPhoto(ref) },
                onBegin = { actions.onBegin(ref.uri) },
                onToggle = { actions.onToggle(ref.uri) },
            )
        }
        if (listing.isLoading) {
            item(key = "loading-more", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { FolderLoading() }
            }
        }
    }
}

private fun LazyGridScope.header(key: String, title: Int) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
        )
    }
}

@Composable
private fun FolderLoading(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.folder_loading)
    LoadingIndicator(modifier.semantics { contentDescription = description })
}

@Composable
private fun FolderError(error: PhotoAccessError) {
    EmptyState(
        icon = Icons.Rounded.FolderOff,
        shape = MaterialShapes.Cookie6Sided,
        title = stringResource(R.string.folder_error_title),
        body = stringResource(
            when (error) {
                PhotoAccessError.PermissionDenied -> R.string.folder_error_access_body
                PhotoAccessError.NotFound -> R.string.folder_error_missing_body
                else -> R.string.folder_error_read_body
            },
        ),
    )
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) { content() }
}

/** "3 folders, 120 photos", leaving out whichever is zero. */
@Composable
private fun folderSummary(listing: FolderListing): String? {
    val folders = listing.subfolders.size
    val photos = listing.photos.size
    return listOfNotNull(
        if (folders > 0) pluralStringResource(R.plurals.folder_count, folders, folders) else null,
        if (photos > 0) pluralStringResource(R.plurals.photo_count, photos, photos) else null,
    ).joinToString(", ").ifEmpty { null }
}
