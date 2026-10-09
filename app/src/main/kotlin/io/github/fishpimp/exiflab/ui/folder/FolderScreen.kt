package io.github.fishpimp.exiflab.ui.folder

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import io.github.fishpimp.exiflab.data.folders.FolderListing
import io.github.fishpimp.exiflab.data.folders.Subfolder
import io.github.fishpimp.exiflab.data.photos.PhotoAccessError
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.designsystem.component.EmptyState
import io.github.fishpimp.exiflab.ui.components.PhotoGridCells
import io.github.fishpimp.exiflab.ui.components.PhotoTile
import io.github.fishpimp.exiflab.ui.components.ScreenScaffold
import io.github.fishpimp.exiflab.ui.components.isLargeFontScale
import io.github.fishpimp.exiflab.ui.navigation.Route

/** Browses one folder of a granted tree: subfolders first, then photos newest first. */
@Composable
fun FolderScreen(
    route: Route.Folder,
    onBack: () -> Unit,
    onOpenFolder: (Route.Folder) -> Unit,
    onOpenPhoto: (PhotoRef) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FolderViewModel = viewModel(factory = FolderViewModel.factory(route)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FolderContent(
        title = route.title,
        state = state,
        onBack = onBack,
        onOpenSubfolder = { onOpenFolder(Route.Folder(route.folderTreeUri, it.documentUri, it.name)) },
        onOpenPhoto = onOpenPhoto,
        modifier = modifier,
    )
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
) {
    val listing = (state as? FolderUiState.Loaded)?.listing
    ScreenScaffold(
        title = title,
        subtitle = listing?.let { folderSummary(it) },
        onBack = onBack,
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
                else -> FolderGrid(listing, onOpenSubfolder, onOpenPhoto)
            }
        }
    }
}

@Composable
private fun FolderGrid(listing: FolderListing, onOpenSubfolder: (Subfolder) -> Unit, onOpenPhoto: (PhotoRef) -> Unit) {
    val showHeaders = listing.subfolders.isNotEmpty() && listing.photos.isNotEmpty()
    val wideFolders = isLargeFontScale()
    LazyVerticalGrid(
        columns = PhotoGridCells,
        modifier = Modifier.widthIn(max = 1200.dp).fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
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
            PhotoTile(ref = ref, onClick = { onOpenPhoto(ref) })
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
