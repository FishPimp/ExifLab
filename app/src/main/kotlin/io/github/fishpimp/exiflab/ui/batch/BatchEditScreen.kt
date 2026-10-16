package io.github.fishpimp.exiflab.ui.batch

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Copyright
import androidx.compose.material.icons.rounded.Difference
import androidx.compose.material.icons.rounded.EditLocationAlt
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.batch.BatchKind
import io.github.fishpimp.exiflab.designsystem.component.EmptyState
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.metadata.edit.StripCategory
import io.github.fishpimp.exiflab.ui.components.ScreenScaffold
import io.github.fishpimp.exiflab.ui.components.isLargeFontScale
import io.github.fishpimp.exiflab.ui.edit.EditorActionRow
import io.github.fishpimp.exiflab.ui.edit.EditorCard
import io.github.fishpimp.exiflab.ui.edit.location.LocationPicker
import io.github.fishpimp.exiflab.ui.edit.time.ShiftTimeContent
import io.github.fishpimp.exiflab.ui.edit.time.TimeShift
import io.github.fishpimp.exiflab.ui.edit.time.TimeZoneWizard
import io.github.fishpimp.exiflab.ui.format.LocationFormat
import io.github.fishpimp.exiflab.ui.map.LatLng
import io.github.fishpimp.exiflab.ui.map.LocationMap
import io.github.fishpimp.exiflab.ui.map.formatted
import java.time.ZoneId
import kotlinx.coroutines.launch

/** Width of the editor's single column on large screens. */
internal val BatchColumnMaxWidth = 720.dp

/**
 * Batch editor for the photos of draft [jobId], opened on the action [kind]: set the values,
 * preview, then start. Starting asks for the notification permission on Android 13+ (the job
 * runs either way) and hands over to [onStarted] with the running job.
 */
@Composable
fun BatchEditScreen(
    jobId: String,
    kind: BatchKind,
    onBack: () -> Unit,
    onStarted: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BatchEditViewModel = viewModel(key = "batch-edit:$jobId:$kind", factory = BatchEditViewModel.factory(jobId, kind)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val currentOnStarted by rememberUpdatedState(onStarted)
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.copyLocationFrom(it.toString()) }
    }
    val openFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        viewModel.onCopyFolderPicked(uri?.toString())
    }
    // The job runs whatever the answer; the notification only adds progress outside the app.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.start() }

    fun launchSafely(launch: () -> Unit) {
        try {
            launch()
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.home_no_picker)) }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BatchEditEvent.Started -> currentOnStarted(event.jobId)
                is BatchEditEvent.Message -> launch { snackbarHostState.showSnackbar(resources.getString(event.message.textRes)) }
            }
        }
    }

    // Leaving the setup page drops what was typed for the author, so that asks first, like the editor.
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    fun typedAuthor() = viewModel.artist.isNotBlank() || viewModel.copyright.isNotBlank()
    BackHandler(enabled = state.page != BatchEditorPage.Setup) { viewModel.back() }
    BackHandler(enabled = state.page == BatchEditorPage.Setup && typedAuthor()) { confirmDiscard = true }

    BatchEditContent(
        state = state,
        artist = viewModel.artist,
        copyright = viewModel.copyright,
        placeQuery = viewModel.placeQuery,
        actions = remember(viewModel, onBack) {
            BatchEditActions(
                onBack = {
                    if (!viewModel.back()) {
                        if (typedAuthor()) confirmDiscard = true else onBack()
                    }
                },
                onShiftChange = viewModel::setShift,
                onOpenLocationPicker = viewModel::openLocationPicker,
                onSetLocation = viewModel::setLocation,
                onCopyLocation = { launchSafely { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } },
                onPlaceQueryChange = viewModel::onPlaceQueryChange,
                onArtistChange = viewModel::onArtistChange,
                onCopyrightChange = viewModel::onCopyrightChange,
                onToggleStrip = viewModel::toggleStrip,
                onSetTimeZones = viewModel::setTimeZones,
                onOpenPreview = viewModel::openPreview,
                onChooseFolder = { launchSafely { openFolder.launch(null) } },
                onClearFolder = viewModel::clearCopyFolder,
                onStart = {
                    val needsAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    if (needsAsk) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) else viewModel.start()
                },
            )
        },
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.edit_discard_title)) },
            text = { Text(stringResource(R.string.batch_discard_author_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onBack()
                    },
                ) { Text(stringResource(R.string.edit_discard_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.edit_keep_editing)) } },
        )
    }
}

/** Everything the batch editor can ask its host to do. Defaults do nothing, for screenshot tests. */
@Immutable
data class BatchEditActions(
    val onBack: () -> Unit = {},
    val onShiftChange: (TimeShift) -> Unit = {},
    val onOpenLocationPicker: () -> Unit = {},
    val onSetLocation: (Double, Double, Double?) -> Unit = { _, _, _ -> },
    val onCopyLocation: () -> Unit = {},
    val onPlaceQueryChange: (String) -> Unit = {},
    val onArtistChange: (String) -> Unit = {},
    val onCopyrightChange: (String) -> Unit = {},
    val onToggleStrip: (StripCategory) -> Unit = {},
    val onSetTimeZones: (ZoneId, ZoneId) -> Unit = { _, _ -> },
    val onOpenPreview: () -> Unit = {},
    val onChooseFolder: () -> Unit = {},
    val onClearFolder: () -> Unit = {},
    val onStart: () -> Unit = {},
)

/** Stateless batch editor (setup, location picker or preview), rendered directly by screenshot tests. */
@Composable
fun BatchEditContent(
    state: BatchEditUiState,
    artist: String,
    copyright: String,
    placeQuery: String,
    actions: BatchEditActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val kind = state.kind
    val photos = state.photos
    when {
        photos !is DraftPhotos.Loaded -> ScreenScaffold(title = stringResource(kind.labelRes), onBack = actions.onBack, modifier = modifier) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                if (photos is DraftPhotos.Missing) {
                    EmptyState(
                        icon = Icons.Rounded.SearchOff,
                        shape = MaterialShapes.Cookie6Sided,
                        title = stringResource(R.string.batch_missing_title),
                        body = stringResource(R.string.batch_missing_body),
                    )
                } else {
                    val description = stringResource(R.string.photo_loading)
                    LoadingIndicator(Modifier.semantics { contentDescription = description })
                }
            }
        }
        state.page == BatchEditorPage.Preview -> BatchPreviewPage(state, artist, copyright, actions, snackbarHostState, modifier)
        state.page == BatchEditorPage.Location -> LocationPicker(
            initial = state.draft.location ?: state.sample?.location,
            query = placeQuery,
            search = state.placeSearch,
            onQueryChange = actions.onPlaceQueryChange,
            onConfirm = actions.onSetLocation,
            onClose = actions.onBack,
        )
        kind == BatchKind.TimeZone -> TimeZoneWizard(
            recorded = state.sample?.summary?.capturedAt,
            recordedOffset = state.sample?.summary?.utcOffset,
            onApply = actions.onSetTimeZones,
            onClose = actions.onBack,
        )
        else -> BatchSetupPage(state, artist, copyright, actions, snackbarHostState, modifier)
    }
}

@Composable
private fun BatchSetupPage(
    state: BatchEditUiState,
    artist: String,
    copyright: String,
    actions: BatchEditActions,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier,
) {
    val kind = state.kind
    val ready = state.draft.operation(kind, artist, copyright) != null
    ScreenScaffold(
        title = stringResource(kind.labelRes),
        subtitle = pluralStringResource(R.plurals.photo_count, state.count, state.count),
        onBack = actions.onBack,
        floatingActionButton = {
            AnimatedVisibility(visible = ready && kind != BatchKind.ShiftTime, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                ExtendedFloatingActionButton(
                    onClick = actions.onOpenPreview,
                    icon = { Icon(Icons.Rounded.Difference, contentDescription = null) },
                    text = { Text(stringResource(R.string.batch_preview_button)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { padding ->
        // The keyboard shrinks the page so the author fields scroll into view above it.
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = BatchColumnMaxWidth)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (kind) {
                    BatchKind.Location -> LocationSetup(state, actions)
                    BatchKind.ShiftTime -> ShiftSetup(state, actions)
                    BatchKind.Author -> AuthorSetup(artist, copyright, actions)
                    BatchKind.Strip -> StripSetup(state.draft.strip, actions.onToggleStrip)
                    BatchKind.TimeZone -> Unit
                }
            }
        }
    }
}

@Composable
private fun LocationSetup(state: BatchEditUiState, actions: BatchEditActions) {
    val location = state.draft.location
    EditorCard(
        icon = Icons.Rounded.Place,
        shape = MaterialShapes.Clover4Leaf,
        title = stringResource(R.string.edit_location_title),
        subtitle = stringResource(if (location != null) R.string.batch_location_chosen else R.string.batch_location_none),
        iconContainer = ExifLabTheme.extendedColors.sensitiveContainer,
        iconContent = ExifLabTheme.extendedColors.onSensitiveContainer,
    ) {
        if (location != null) {
            val latLng = LatLng(location.latitude, location.longitude)
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LocationMap(
                    marker = latLng,
                    onClick = actions.onOpenLocationPicker,
                    modifier = Modifier.fillMaxWidth().height(if (isLargeFontScale()) 240.dp else 180.dp),
                )
                Text(latLng.formatted(), style = ExifLabTheme.extendedTypography.monoMedium)
                Text(
                    LocationFormat.degreesMinutesSeconds(location.latitude, location.longitude),
                    style = ExifLabTheme.extendedTypography.monoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column {
            EditorActionRow(
                Icons.Rounded.EditLocationAlt,
                stringResource(if (location != null) R.string.edit_location_change else R.string.edit_location_add),
                stringResource(R.string.edit_location_change_body),
                actions.onOpenLocationPicker,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                EditorActionRow(
                    Icons.Rounded.PhotoLibrary,
                    stringResource(R.string.edit_location_copy),
                    stringResource(R.string.edit_location_copy_body),
                    actions.onCopyLocation,
                    enabled = !state.readingOtherPhoto,
                    modifier = Modifier.weight(1f),
                )
                if (state.readingOtherPhoto) {
                    val reading = stringResource(R.string.edit_location_copy_reading)
                    LoadingIndicator(Modifier.padding(end = 16.dp).size(32.dp).semantics { contentDescription = reading })
                }
            }
        }
    }
}

@Composable
private fun ShiftSetup(state: BatchEditUiState, actions: BatchEditActions) {
    val sample = state.sample
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ShiftTimeContent(
                shift = state.draft.shift,
                capturedAt = sample?.summary?.capturedAt,
                offset = sample?.summary?.utcOffset,
                onShiftChange = actions.onShiftChange,
                onApply = actions.onOpenPreview,
                onCancel = actions.onBack,
            )
        }
    }
    val name = (state.photos as? DraftPhotos.Loaded)?.photos?.firstOrNull()?.displayName
    if (name != null) {
        Text(
            stringResource(R.string.batch_shift_sample, name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun AuthorSetup(artist: String, copyright: String, actions: BatchEditActions) {
    EditorCard(
        icon = Icons.Rounded.Person,
        shape = MaterialShapes.Sunny,
        title = stringResource(R.string.edit_author_title),
        subtitle = stringResource(R.string.batch_author_body),
        iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
        iconContent = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = artist,
                onValueChange = actions.onArtistChange,
                label = { Text(stringResource(R.string.edit_author_artist)) },
                leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                supportingText = { Text(stringResource(R.string.batch_author_keep)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = copyright,
                onValueChange = actions.onCopyrightChange,
                label = { Text(stringResource(R.string.edit_author_copyright)) },
                leadingIcon = { Icon(Icons.Rounded.Copyright, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.edit_author_copyright_hint)) },
                supportingText = { Text(stringResource(R.string.batch_author_keep)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The strip categories as checkboxes with explanations; "Everything" covers (and checks) all others. */
@Composable
fun StripSetup(selected: Set<StripCategory>, onToggle: (StripCategory) -> Unit, modifier: Modifier = Modifier) {
    val everything = StripCategory.All in selected
    EditorCard(
        icon = BatchKind.Strip.icon,
        shape = MaterialShapes.Pill,
        title = stringResource(R.string.batch_strip_title),
        subtitle = stringResource(R.string.batch_strip_body),
        iconContainer = ExifLabTheme.extendedColors.sensitiveContainer,
        iconContent = ExifLabTheme.extendedColors.onSensitiveContainer,
        modifier = modifier,
    ) {
        Column {
            StripOrder.forEachIndexed { index, category ->
                if (index == 1) HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                val covered = everything && category != StripCategory.All
                StripRow(
                    category = category,
                    checked = category in selected || covered,
                    enabled = !covered,
                    onToggle = { onToggle(category) },
                )
            }
        }
    }
}

@Composable
private fun StripRow(category: StripCategory, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(category.titleRes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            Text(
                stringResource(category.bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
    }
}

/** "Everything" first, then the single categories, roughly by how private they are. */
private val StripOrder = listOf(
    StripCategory.All,
    StripCategory.Location,
    StripCategory.DeviceIdentifiers,
    StripCategory.PersonNames,
    StripCategory.People,
    StripCategory.DeviceInfo,
    StripCategory.Dates,
    StripCategory.MakerNotes,
    StripCategory.Thumbnail,
    StripCategory.EditHistory,
)

private const val DISABLED_ALPHA = 0.6f

private val BatchEditMessage.textRes: Int
    get() = when (this) {
        BatchEditMessage.LocationCopied -> R.string.edit_location_copied
        BatchEditMessage.OtherPhotoNoLocation -> R.string.edit_location_copy_none
        BatchEditMessage.OtherPhotoLocationRemoved -> R.string.edit_location_copy_removed
        BatchEditMessage.OtherPhotoUnreadable -> R.string.edit_location_copy_failed
        BatchEditMessage.FolderUnavailable -> R.string.batch_folder_unavailable
        BatchEditMessage.StartFailed -> R.string.batch_start_failed
    }
