package io.github.fishpimp.exiflab.ui.photo

import android.content.ActivityNotFoundException
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.ImageNotSupported
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SaveAs
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.photos.PhotoFormats
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.photos.formatLabel
import io.github.fishpimp.exiflab.designsystem.component.EmptyState
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.SensitiveFinding
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory
import io.github.fishpimp.exiflab.ui.components.ScreenScaffold
import io.github.fishpimp.exiflab.ui.components.messageRes
import kotlinx.coroutines.launch

/** Width from which the overview puts the preview next to the camera summary. */
private val WideOverviewMinWidth = 600.dp

private val SingleColumnMaxWidth = 720.dp
private val TagPaneMaxWidth = 880.dp
private val CompactGutter = 16.dp
private val PaneGutter = 24.dp

/**
 * Shows one photo's metadata: preview and summary, privacy badges, location and every tag.
 *
 * @param onOpenPhoto shows another photo: a copy the user saved, or the original file they picked.
 */
@Composable
fun PhotoScreen(
    ref: PhotoRef,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPhoto: (PhotoRef) -> Unit = {},
    viewModel: PhotoViewModel = viewModel(key = "photo:${ref.uri}", factory = PhotoViewModel.factory(ref)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val currentOnOpenPhoto by rememberUpdatedState(onOpenPhoto)

    val report = (state.load as? PhotoLoad.Loaded)?.report
    // Keep the original type so the copy opens in the same apps; RAW files often have no registered type.
    val copyMimeType = ref.mimeType ?: report?.format?.mimeType ?: GENERIC_MIME_TYPE
    val createCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(copyMimeType)) { uri ->
        uri?.let(viewModel::saveCopy)
    }
    val openOriginal = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::openOriginal)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PhotoEvent.OpenPhoto -> currentOnOpenPhoto(event.ref)
                is PhotoEvent.CopyFailed -> launch { snackbarHostState.showSnackbar(resources.getString(R.string.photo_error_write)) }
                is PhotoEvent.OpenFailed -> launch { snackbarHostState.showSnackbar(resources.getString(event.error.messageRes)) }
            }
        }
    }

    fun launchPicker(launch: () -> Unit) {
        try {
            launch()
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.home_no_picker)) }
        }
    }

    PhotoContent(
        ref = ref,
        state = state,
        query = viewModel.query,
        actions = remember(viewModel, onBack, createCopy, openOriginal, report?.fileName) {
            PhotoActions(
                onBack = onBack,
                onRetry = viewModel::reload,
                onQueryChange = viewModel::onQueryChange,
                onModeChange = viewModel::setMode,
                onToggleFilter = viewModel::toggleFilter,
                onClearFilter = viewModel::clearFilter,
                onToggleSection = viewModel::toggleSection,
                onSetAllExpanded = viewModel::setAllExpanded,
                onSaveCopy = {
                    val suggestedName = ref.displayName ?: report?.fileName ?: resources.getString(R.string.photo_untitled)
                    launchPicker { createCopy.launch(suggestedName) }
                },
                onOpenOriginal = { launchPicker { openOriginal.launch(PhotoFormats.documentPickerMimeTypes) } },
            )
        },
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

/** Everything the viewer can ask its host to do. Defaults do nothing, for previews and screenshot tests. */
@Immutable
data class PhotoActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onModeChange: (ValueMode) -> Unit = {},
    val onToggleFilter: (SensitivityCategory) -> Unit = {},
    val onClearFilter: () -> Unit = {},
    val onToggleSection: (String) -> Unit = {},
    val onSetAllExpanded: (Boolean) -> Unit = {},
    val onSaveCopy: () -> Unit = {},
    val onOpenOriginal: () -> Unit = {},
)

/**
 * Stateless viewer layout, rendered directly by screenshot tests. One column on phones; on
 * expanded windows (840dp and wider) the overview and the tag browser sit in two panes that
 * scroll on their own.
 *
 * @param listState scroll state of the single column, or of the tag pane in two-pane mode.
 */
@Composable
fun PhotoContent(
    ref: PhotoRef,
    state: PhotoUiState,
    query: String,
    actions: PhotoActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    listState: LazyListState = rememberLazyListState(),
) {
    val report = (state.load as? PhotoLoad.Loaded)?.report
    val name = ref.displayName ?: report?.fileName ?: stringResource(R.string.photo_untitled)
    val copier = rememberClipboardCopier(snackbarHostState)
    ScreenScaffold(
        title = remember(name) { breakableFileName(name) },
        subtitle = fileSubtitle(ref, report),
        onBack = actions.onBack,
        actions = {
            if (report != null) {
                PhotoMenu(
                    onCopyAll = { copier.copyAll(report) },
                    onSaveCopy = actions.onSaveCopy,
                    onOpenOriginal = actions.onOpenOriginal.takeIf { ref.origin == PhotoOrigin.Picker || ref.origin == PhotoOrigin.Share },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val load = state.load) {
                PhotoLoad.Loading -> {
                    val description = stringResource(R.string.photo_loading)
                    LoadingIndicator(Modifier.align(Alignment.Center).semantics { contentDescription = description })
                }
                is PhotoLoad.Failed -> LoadError(load.error, actions.onRetry)
                is PhotoLoad.Loaded -> LoadedPhoto(ref, load.report, name, state, query, actions, copier, listState)
            }
            if (state.isWorking) {
                val description = stringResource(R.string.photo_working)
                ContainedLoadingIndicator(Modifier.align(Alignment.Center).semantics { contentDescription = description })
            }
        }
    }
}

@Composable
private fun LoadedPhoto(
    ref: PhotoRef,
    report: MetadataReport,
    name: String,
    state: PhotoUiState,
    query: String,
    actions: PhotoActions,
    copier: ClipboardCopier,
    listState: LazyListState,
) {
    var showFullScreen by rememberSaveable { mutableStateOf(false) }
    var detailsKey by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val twoPane = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val findings = remember(report) { TagBrowserBuilder.visibleFindings(report) }
    val filterCount = state.filter?.let { category -> findings.firstOrNull { it.category == category }?.tagKeys?.size } ?: 0

    val browserCallbacks = remember(actions, copier) {
        TagBrowserCallbacks(
            onQueryChange = actions.onQueryChange,
            onModeChange = actions.onModeChange,
            onToggleSection = actions.onToggleSection,
            onSetAllExpanded = actions.onSetAllExpanded,
            onClearFilter = actions.onClearFilter,
            onCopyValue = { row -> copier.copyValue(row.tag, row.value) },
            onCopyNameAndValue = { row -> copier.copyNameAndValue(row.tag, row.value) },
            onShowDetails = { row -> detailsKey = row.tag.key },
        )
    }
    val onToggleFilter: (SensitivityCategory) -> Unit = { category ->
        val turningOn = state.filter != category
        actions.onToggleFilter(category)
        // On one column the tags are far below the badges; bring the filtered list into view.
        if (turningOn && !twoPane) scope.launch { listState.animateScrollToItem(1) }
    }
    val overview: @Composable (Modifier) -> Unit = { modifier ->
        PhotoOverview(
            ref = ref,
            report = report,
            findings = findings,
            name = name,
            filter = state.filter,
            onOpenPreview = { showFullScreen = true },
            onToggleFilter = onToggleFilter,
            onCopyCoordinates = copier::copyCoordinates,
            onOpenOriginal = actions.onOpenOriginal,
            onSaveCopy = actions.onSaveCopy,
            modifier = modifier,
        )
    }

    if (twoPane) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val overviewWidth = (maxWidth * 0.4f).coerceIn(340.dp, 480.dp)
            val tagPaneWidth = (maxWidth - overviewWidth).coerceAtMost(TagPaneMaxWidth)
            val wideRows = tagPaneWidth - PaneGutter * 2 >= WIDE_ROWS_MIN_WIDTH
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .width(overviewWidth)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = PaneGutter, end = 12.dp, bottom = 32.dp),
                ) {
                    overview(Modifier)
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopStart) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.widthIn(max = TagPaneMaxWidth).fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 32.dp),
                    ) {
                        tagBrowser(
                            browser = state.browser,
                            query = query,
                            mode = state.mode,
                            filter = state.filter,
                            filterCount = filterCount,
                            callbacks = browserCallbacks,
                            gutter = PaneGutter,
                            wideRows = wideRows,
                        )
                    }
                }
            }
        }
    } else {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val wideRows = maxWidth.coerceAtMost(SingleColumnMaxWidth) - CompactGutter * 2 >= WIDE_ROWS_MIN_WIDTH
            LazyColumn(
                state = listState,
                modifier = Modifier.widthIn(max = SingleColumnMaxWidth).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                item(key = "overview", contentType = "overview") { overview(Modifier.padding(horizontal = CompactGutter)) }
                tagBrowser(
                    browser = state.browser,
                    query = query,
                    mode = state.mode,
                    filter = state.filter,
                    filterCount = filterCount,
                    callbacks = browserCallbacks,
                    gutter = CompactGutter,
                    wideRows = wideRows,
                )
            }
        }
    }

    if (showFullScreen) {
        FullScreenPreview(ref, stringResource(R.string.photo_preview_description, name)) { showFullScreen = false }
    }
    detailsKey?.let { key ->
        val match = remember(report, key) {
            report.directories.firstNotNullOfOrNull { directory -> directory.tags.firstOrNull { it.key == key }?.let { directory to it } }
        }
        if (match == null) {
            detailsKey = null
        } else {
            val (directory, tag) = match
            val shown = if (state.mode == ValueMode.Raw) tag.rawValue else tag.displayValue
            TagDetailsSheet(
                tag = tag,
                directoryName = directory.name,
                onCopyValue = { copier.copyValue(tag, shown) },
                onCopyNameAndValue = { copier.copyNameAndValue(tag, shown) },
                onDismiss = { detailsKey = null },
            )
        }
    }
}

/** Preview, summary, badges and location: everything above the tag list (or left of it). */
@Composable
private fun PhotoOverview(
    ref: PhotoRef,
    report: MetadataReport,
    findings: List<SensitiveFinding>,
    name: String,
    filter: SensitivityCategory?,
    onOpenPreview: () -> Unit,
    onToggleFilter: (SensitivityCategory) -> Unit,
    onCopyCoordinates: (String) -> Unit,
    onOpenOriginal: () -> Unit,
    onSaveCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= WideOverviewMinWidth) {
                // Medium widths: the preview beside camera and capture facts, the numbers full width below.
                Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        PhotoPreview(ref, report, name, onOpenPreview, Modifier.weight(1f), maxHeight = 360.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            CameraSummary(report.summary)
                            PhotoFacts(ref, report)
                        }
                    }
                    SpecGrid(report.summary)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    PhotoPreview(ref, report, name, onOpenPreview)
                    CameraSummary(report.summary)
                    SpecGrid(report.summary)
                    PhotoFacts(ref, report, Modifier.padding(top = 4.dp))
                }
            }
        }
        if (!ref.writable) ReadOnlyBanner(ref.origin)
        PrivacySection(findings, filter, onToggleFilter)
        LocationSection(
            location = report.location,
            status = report.locationStatus,
            origin = ref.origin,
            onCopyCoordinates = onCopyCoordinates,
            onOpenOriginal = onOpenOriginal,
            onSaveCopy = onSaveCopy,
        )
    }
}

@Composable
private fun PhotoMenu(
    onCopyAll: () -> Unit,
    onSaveCopy: () -> Unit,
    onOpenOriginal: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.photo_more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.photo_copy_all)) },
                leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                onClick = {
                    open = false
                    onCopyAll()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.photo_save_copy)) },
                leadingIcon = { Icon(Icons.Rounded.SaveAs, contentDescription = null) },
                onClick = {
                    open = false
                    onSaveCopy()
                },
            )
            if (onOpenOriginal != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.photo_open_original)) },
                    leadingIcon = { Icon(Icons.Rounded.FileOpen, contentDescription = null) },
                    onClick = {
                        open = false
                        onOpenOriginal()
                    },
                )
            }
        }
    }
}

@Composable
private fun LoadError(error: PhotoLoadError, onRetry: () -> Unit) {
    val (icon, title, body) = when (error) {
        PhotoLoadError.Unsupported -> Triple(Icons.Rounded.ImageNotSupported, R.string.photo_load_unsupported_title, R.string.photo_load_unsupported_body)
        PhotoLoadError.Damaged -> Triple(Icons.Rounded.BrokenImage, R.string.photo_load_damaged_title, R.string.photo_load_damaged_body)
        PhotoLoadError.AccessLost -> Triple(Icons.Rounded.LinkOff, R.string.photo_load_access_title, R.string.photo_load_access_body)
        PhotoLoadError.NotFound -> Triple(Icons.Rounded.HideImage, R.string.photo_load_missing_title, R.string.photo_load_missing_body)
        PhotoLoadError.ReadFailed -> Triple(Icons.Rounded.BrokenImage, R.string.photo_load_failed_title, R.string.photo_load_failed_body)
    }
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        EmptyState(
            icon = icon,
            shape = MaterialShapes.Cookie6Sided,
            title = stringResource(title),
            body = stringResource(body),
            actionLabel = if (error.canRetry) stringResource(R.string.photo_retry) else null,
            actionIcon = Icons.Rounded.Refresh,
            onAction = if (error.canRetry) onRetry else null,
        )
    }
}

/**
 * [name] with invisible break opportunities after "_" and "-", so a long camera file name such as
 * "PXL_20260614_174207123.jpg" wraps between its parts instead of in the middle of a number.
 */
internal fun breakableFileName(name: String): String =
    buildString(name.length + 8) {
        name.forEach { char ->
            append(char)
            if (char == '_' || char == '-') append(ZERO_WIDTH_SPACE)
        }
    }

private const val ZERO_WIDTH_SPACE = '\u200B'

/** "JPEG · 3.2 MB" under the title. */
@Composable
private fun fileSubtitle(ref: PhotoRef, report: MetadataReport?): String? {
    val context = LocalContext.current
    val size = report?.fileSize ?: ref.size
    return remember(ref, report, context) {
        listOfNotNull(
            ref.formatLabel ?: report?.format?.displayName,
            size?.let { Formatter.formatShortFileSize(context, it) },
        ).joinToString(" · ").ifEmpty { null }
    }
}

private const val GENERIC_MIME_TYPE = "application/octet-stream"
