package io.github.fishpimp.exiflab.ui.home

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.batch.ActiveBatch
import io.github.fishpimp.exiflab.data.photos.MAX_PHOTOS_PER_OPEN
import io.github.fishpimp.exiflab.data.photos.PhotoFormats
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.designsystem.component.ShapeIcon
import io.github.fishpimp.exiflab.ui.components.messageRes
import kotlinx.coroutines.launch

private val PagePadding = 20.dp

/**
 * Start screen: open photos through the photo picker or file picker, jump to granted folders,
 * reopen recent photos.
 *
 * @param onOpenPhotos shows the photos the user picked or tapped (never an empty list).
 * @param onBrowseFolders switches to the Library tab.
 */
@Composable
fun HomeScreen(
    onOpenPhotos: (List<PhotoRef>) -> Unit,
    onBrowseFolders: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenBatchJob: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val recents by viewModel.recents.collectAsStateWithLifecycle()
    val activeBatches by viewModel.activeBatches.collectAsStateWithLifecycle()
    val isOpening by viewModel.isOpening.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val currentOnOpenPhotos by rememberUpdatedState(onOpenPhotos)

    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS_PER_OPEN),
    ) { uris -> viewModel.open(uris, PhotoOrigin.Picker) }
    val openFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.open(uris, PhotoOrigin.Document) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.Open -> currentOnOpenPhotos(event.refs)
                is HomeEvent.Failed -> launch { snackbarHostState.showSnackbar(resources.getString(event.error.messageRes)) }
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshRecents()
        onPauseOrDispose { }
    }

    fun launchPicker(launch: () -> Unit) {
        try {
            launch()
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.home_no_picker)) }
        }
    }

    HomeContent(
        recents = recents,
        isOpening = isOpening,
        onOpenPhotoPicker = {
            launchPicker { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        },
        onOpenFiles = { launchPicker { openFiles.launch(PhotoFormats.documentPickerMimeTypes) } },
        onBrowseFolders = onBrowseFolders,
        onOpenRecent = { onOpenPhotos(listOf(it)) },
        onOpenPrivacy = onOpenPrivacy,
        activeBatches = activeBatches,
        onOpenBatchJob = onOpenBatchJob,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

/** Stateless Home layout, rendered directly by screenshot tests. */
@Composable
fun HomeContent(
    recents: List<PhotoRef>,
    isOpening: Boolean,
    onOpenPhotoPicker: () -> Unit,
    onOpenFiles: () -> Unit,
    onBrowseFolders: () -> Unit,
    onOpenRecent: (PhotoRef) -> Unit,
    onOpenPrivacy: () -> Unit,
    modifier: Modifier = Modifier,
    activeBatches: List<ActiveBatch> = emptyList(),
    onOpenBatchJob: (String) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                contentPadding = PaddingValues(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { HomeHero(Modifier.padding(horizontal = PagePadding)) }
                item {
                    HomeActions(
                        onOpenPhotos = onOpenPhotoPicker,
                        onOpenFiles = onOpenFiles,
                        onBrowseFolders = onBrowseFolders,
                        enabled = !isOpening,
                        modifier = Modifier.padding(horizontal = PagePadding),
                    )
                }
                items(activeBatches, key = { "batch-${it.id}" }) { batch ->
                    ActiveBatchCard(batch, onClick = { onOpenBatchJob(batch.id) }, modifier = Modifier.padding(horizontal = PagePadding))
                }
                if (recents.isNotEmpty()) {
                    item {
                        RecentPhotosRow(
                            recents = recents,
                            onOpen = onOpenRecent,
                            horizontalPadding = PagePadding,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                item { BatchHintCard(Modifier.padding(horizontal = PagePadding)) }
                item { PrivacyCard(onClick = onOpenPrivacy, modifier = Modifier.padding(horizontal = PagePadding)) }
            }
            if (isOpening) {
                val description = stringResource(R.string.home_opening)
                ContainedLoadingIndicator(
                    Modifier
                        .align(Alignment.Center)
                        .semantics { contentDescription = description },
                )
            }
            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }
}

@Composable
private fun HomeHero(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp)) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_tagline),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** How to edit many photos at once: there is no separate entry; it starts from a selection. */
@Composable
private fun BatchHintCard(modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(20.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ShapeIcon(
                icon = Icons.Rounded.Checklist,
                shape = MaterialShapes.Clover4Leaf,
                size = 56.dp,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_batch_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.home_batch_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A batch edit running in the background: its progress, and a way back to it. */
@Composable
private fun ActiveBatchCard(batch: ActiveBatch, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val progress = if (batch.total > 0) batch.done.toFloat() / batch.total else 0f
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularWavyProgressIndicator(progress = { progress }, modifier = Modifier.size(56.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_batch_running), style = MaterialTheme.typography.titleMedium)
                Text(
                    listOf(
                        batch.title,
                        pluralStringResource(R.plurals.batch_notification_progress, batch.total, batch.done, batch.total),
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
private fun PrivacyCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ShapeIcon(
                icon = Icons.Rounded.Lock,
                shape = MaterialShapes.Cookie7Sided,
                size = 56.dp,
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            )
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_privacy_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.home_privacy_body), style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
        }
    }
}
