package io.github.fishpimp.exiflab.ui.edit

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.rounded.Difference
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.photos.PhotoFormats
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.designsystem.component.EmptyState
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.ui.components.ScreenScaffold
import io.github.fishpimp.exiflab.ui.edit.location.LocationPicker
import io.github.fishpimp.exiflab.ui.edit.time.SetDateTimeDialog
import io.github.fishpimp.exiflab.ui.edit.time.SetOffsetDialog
import io.github.fishpimp.exiflab.ui.edit.time.ShiftTimeSheet
import io.github.fishpimp.exiflab.ui.edit.time.TimeZoneWizard
import io.github.fishpimp.exiflab.ui.photo.breakableFileName
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.launch

private val SingleColumnMaxWidth = 720.dp
private val CompactGutter = 16.dp
private val PaneGutter = 24.dp

/**
 * Edits one photo's metadata. Back with queued edits asks before discarding them; saving returns
 * to the viewer (which reloads and offers Undo), or opens the new copy.
 *
 * @param onFinished called after a save: with the copy to open, or null to go back to the photo.
 */
@Composable
fun EditScreen(
    ref: PhotoRef,
    onBack: () -> Unit,
    onFinished: (PhotoRef?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EditViewModel = viewModel(key = "edit:${ref.uri}", factory = EditViewModel.factory(ref)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val currentOnFinished by rememberUpdatedState(onFinished)
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var destination by remember { mutableStateOf<EditEvent.ChooseDestination?>(null) }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(destination?.mimeType ?: GENERIC_MIME_TYPE),
    ) { uri ->
        val request = destination
        destination = null
        if (request != null) viewModel.onDestinationChosen(request.mode, uri?.toString())
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.copyLocationFrom(it.toString()) }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.copyLocationFrom(it.toString()) }
    }

    fun launchSafely(launch: () -> Unit, onUnavailable: () -> Unit = {}) {
        try {
            launch()
        } catch (_: ActivityNotFoundException) {
            onUnavailable()
            scope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.home_no_picker)) }
        }
    }

    LaunchedEffect(destination) {
        val request = destination ?: return@LaunchedEffect
        // Launched after the recomposition that gave the launcher this request's MIME type.
        launchSafely({ createDocument.launch(request.suggestedName) }, onUnavailable = {
            destination = null
            viewModel.onDestinationChosen(request.mode, null)
        })
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is EditEvent.Finished -> currentOnFinished(event.openCopy)
                is EditEvent.ChooseDestination -> destination = event
                is EditEvent.Message -> launch {
                    val openInstead = event.message == EditMessage.OtherPhotoLocationRemoved || event.message == EditMessage.OtherPhotoNoLocation
                    val result = snackbarHostState.showSnackbar(
                        message = resources.getString(event.message.textRes),
                        actionLabel = if (openInstead) resources.getString(R.string.edit_location_copy_open_file) else null,
                        duration = if (openInstead) SnackbarDuration.Long else SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        launchSafely({ openFile.launch(PhotoFormats.documentPickerMimeTypes) })
                    }
                }
            }
        }
    }

    // Sub-pages close on back; the overview asks before throwing queued edits away. Saving ignores back.
    BackHandler(enabled = state.isSaving) {}
    BackHandler(enabled = !state.isSaving && state.page != EditorPage.Overview) { viewModel.closePage() }
    BackHandler(enabled = !state.isSaving && state.page == EditorPage.Overview && state.hasEdits) { confirmDiscard = true }

    val hasEdits by rememberUpdatedState(state.hasEdits)
    val currentOnBack by rememberUpdatedState(onBack)
    val requestBack: () -> Unit = remember {
        { if (hasEdits) confirmDiscard = true else currentOnBack() }
    }

    EditContent(
        ref = ref,
        state = state,
        fieldQuery = viewModel.fieldQuery,
        fieldDraft = viewModel.fieldDraft,
        placeQuery = viewModel.placeQuery,
        actions = remember(viewModel, requestBack) {
            EditActions(
                onBack = requestBack,
                onRetry = viewModel::reload,
                onRemoveEdit = viewModel::removeEdit,
                onDiscardAll = { confirmDiscard = true },
                onOpenPage = viewModel::openPage,
                onClosePage = viewModel::closePage,
                onSetCaptureTime = viewModel::setCaptureTime,
                onShiftTime = viewModel::shiftTime,
                onChangeTimeZone = viewModel::changeTimeZone,
                onSetUtcOffset = viewModel::setUtcOffset,
                onSetLocation = viewModel::setLocation,
                onRemoveLocation = viewModel::removeLocation,
                onCopyLocation = { launchSafely({ pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) },
                onPlaceQueryChange = viewModel::onPlaceQueryChange,
                onSetAuthorship = viewModel::setAuthorship,
                fields = FieldCallbacks(
                    onQueryChange = viewModel::onFieldQueryChange,
                    onEdit = viewModel::editField,
                    onDraftChange = viewModel::onFieldDraftChange,
                    onApply = { viewModel.applyField() },
                    onCancel = viewModel::closeFieldEditor,
                    onRemove = viewModel::removeField,
                    onRevert = viewModel::revertField,
                    onAdd = viewModel::addField,
                ),
                onSave = viewModel::save,
                onDismissSaveFailure = viewModel::dismissSaveFailure,
                onFinishAfterSave = viewModel::finishAfterSave,
            )
        },
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.edit_discard_title)) },
            text = { Text(pluralStringResource(R.plurals.edit_discard_body, state.edits.size, state.edits.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        viewModel.discardEdits()
                        onBack()
                    },
                ) { Text(stringResource(R.string.edit_discard_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.edit_keep_editing)) } },
        )
    }
}

/** Everything the editor can ask its host to do. Defaults do nothing, for screenshot tests. */
@Immutable
data class EditActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onRemoveEdit: (Long) -> Unit = {},
    val onDiscardAll: () -> Unit = {},
    val onOpenPage: (EditorPage) -> Unit = {},
    val onClosePage: () -> Unit = {},
    val onSetCaptureTime: (LocalDateTime, ZoneOffset?, Boolean) -> Unit = { _, _, _ -> },
    val onShiftTime: (Duration) -> Unit = {},
    val onChangeTimeZone: (ZoneId, ZoneId) -> Unit = { _, _ -> },
    val onSetUtcOffset: (ZoneOffset) -> Unit = {},
    val onSetLocation: (Double, Double, Double?) -> Unit = { _, _, _ -> },
    val onRemoveLocation: (Boolean) -> Unit = {},
    val onCopyLocation: () -> Unit = {},
    val onPlaceQueryChange: (String) -> Unit = {},
    val onSetAuthorship: (String, String) -> Unit = { _, _ -> },
    val fields: FieldCallbacks = FieldCallbacks(),
    val onSave: (SaveMode) -> Unit = {},
    val onDismissSaveFailure: () -> Unit = {},
    val onFinishAfterSave: () -> Unit = {},
)

/**
 * Stateless editor, rendered directly by screenshot tests: the overview, or one of its pages
 * (review, location picker, time zone wizard).
 */
@Composable
fun EditContent(
    ref: PhotoRef,
    state: EditUiState,
    fieldQuery: String,
    placeQuery: String,
    actions: EditActions,
    modifier: Modifier = Modifier,
    fieldDraft: String = "",
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    listState: LazyListState = rememberLazyListState(),
) {
    val report = state.report
    AnimatedContent(
        targetState = if (report == null) EditorPage.Overview else state.page,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "editorPage",
        modifier = modifier,
    ) { page ->
        when {
            report == null || page == EditorPage.Overview -> EditOverview(ref, state, fieldQuery, fieldDraft, actions, snackbarHostState, listState)
            page == EditorPage.Review -> ReviewContent(
                ref = ref,
                report = report,
                state = state,
                onClose = actions.onClosePage,
                onSave = actions.onSave,
                onDismissFailure = actions.onDismissSaveFailure,
                onFinish = actions.onFinishAfterSave,
            )
            page == EditorPage.Location -> LocationPicker(
                initial = report.locationAfter(state.edits).location,
                query = placeQuery,
                search = state.placeSearch,
                onQueryChange = actions.onPlaceQueryChange,
                onConfirm = actions.onSetLocation,
                onClose = actions.onClosePage,
            )
            else -> {
                // A new time zone fix replaces a queued one, so it starts from the time without it.
                val time = report.captureTimeAfter(state.edits.filterNot { it.slot == EditSlot.TimeZone })
                TimeZoneWizard(
                    recorded = time.local,
                    recordedOffset = time.offset,
                    onApply = actions.onChangeTimeZone,
                    onClose = actions.onClosePage,
                )
            }
        }
    }
}

@Composable
private fun EditOverview(
    ref: PhotoRef,
    state: EditUiState,
    fieldQuery: String,
    fieldDraft: String,
    actions: EditActions,
    snackbarHostState: SnackbarHostState,
    listState: LazyListState,
) {
    val report = state.report
    val name = ref.displayName ?: report?.fileName ?: stringResource(R.string.photo_untitled)
    ScreenScaffold(
        title = stringResource(R.string.edit_title),
        subtitle = remember(name) { breakableFileName(name) },
        onBack = actions.onBack,
        floatingActionButton = {
            AnimatedVisibility(visible = state.hasEdits && report != null, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                ExtendedFloatingActionButton(
                    onClick = { actions.onOpenPage(EditorPage.Review) },
                    icon = { Icon(Icons.Rounded.Difference, contentDescription = null) },
                    text = { Text(pluralStringResource(R.plurals.edit_review_button, state.edits.size, state.edits.size)) },
                    expanded = true,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // The keyboard shrinks the list so an inline field editor near the bottom can scroll into view.
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            when (val load = state.load) {
                EditLoad.Loading -> {
                    val description = stringResource(R.string.photo_loading)
                    LoadingIndicator(Modifier.align(Alignment.Center).semantics { contentDescription = description })
                }
                is EditLoad.Failed -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Rounded.BrokenImage,
                        shape = MaterialShapes.Cookie6Sided,
                        title = stringResource(R.string.edit_load_failed_title),
                        body = stringResource(R.string.edit_load_failed_body),
                        actionLabel = if (load.error.canRetry) stringResource(R.string.photo_retry) else null,
                        actionIcon = Icons.Rounded.Refresh,
                        onAction = if (load.error.canRetry) actions.onRetry else null,
                    )
                }
                is EditLoad.Loaded -> EditorSections(load.report, state, fieldQuery, fieldDraft, actions, listState)
            }
        }
    }
}

/** One column on phones; two panes (tools left, all fields right) on expanded widths. */
@Composable
private fun EditorSections(
    report: MetadataReport,
    state: EditUiState,
    fieldQuery: String,
    fieldDraft: String,
    actions: EditActions,
    listState: LazyListState,
) {
    var timeTool by rememberSaveable { mutableStateOf<TimeTool?>(null) }
    var removingLocation by rememberSaveable { mutableStateOf(false) }
    val twoPane = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val tools: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.edits.isNotEmpty()) PendingEditsCard(state.edits, actions.onRemoveEdit, actions.onDiscardAll)
            DateTimeCard(report, state.edits, onOpenTool = { timeTool = it }, onFixTimeZone = { actions.onOpenPage(EditorPage.TimeZone) })
            LocationCard(
                report = report,
                edits = state.edits,
                readingOtherPhoto = state.readingOtherPhoto,
                onChangeLocation = { actions.onOpenPage(EditorPage.Location) },
                onCopyFromPhoto = actions.onCopyLocation,
                onRemoveLocation = { removingLocation = true },
            )
            AuthorCard(report, state.edits, actions.onSetAuthorship)
        }
    }
    val fieldsList: @Composable (Modifier, Dp, Boolean) -> Unit = { modifier, gutter, includeTools ->
        LazyColumn(state = listState, modifier = modifier, contentPadding = PaddingValues(bottom = FAB_CLEARANCE)) {
            if (includeTools) {
                item(key = "tools", contentType = "tools") { tools(Modifier.padding(horizontal = gutter).padding(top = 8.dp, bottom = 24.dp)) }
            }
            allFieldsItems(state.fields, state.fieldEditor, fieldDraft, fieldQuery, actions.fields, gutter)
        }
    }

    if (twoPane) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val toolsWidth = (maxWidth * 0.45f).coerceIn(360.dp, 560.dp)
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .width(toolsWidth)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = PaneGutter, end = 12.dp, top = 8.dp, bottom = FAB_CLEARANCE),
                ) { tools(Modifier) }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopStart) {
                    fieldsList(Modifier.widthIn(max = SingleColumnMaxWidth).fillMaxSize(), PaneGutter, false)
                }
            }
        }
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            fieldsList(Modifier.widthIn(max = SingleColumnMaxWidth).fillMaxSize(), CompactGutter, true)
        }
    }

    val time = report.captureTimeAfter(state.edits)
    when (timeTool) {
        TimeTool.SetDateTime -> SetDateTimeDialog(
            initial = time.local ?: LocalDateTime.now().withNano(0),
            initialOffset = null,
            onConfirm = { local, offset, alsoModify ->
                timeTool = null
                actions.onSetCaptureTime(local, offset, alsoModify)
            },
            onDismiss = { timeTool = null },
        )
        TimeTool.Shift -> ShiftTimeSheet(
            capturedAt = time.local,
            offset = time.offset,
            onApply = {
                timeTool = null
                actions.onShiftTime(it)
            },
            onDismiss = { timeTool = null },
        )
        TimeTool.Offset -> SetOffsetDialog(
            initial = time.offset,
            onConfirm = {
                timeTool = null
                actions.onSetUtcOffset(it)
            },
            onDismiss = { timeTool = null },
        )
        null -> Unit
    }
    if (removingLocation) {
        RemoveLocationDialog(
            onConfirm = {
                removingLocation = false
                actions.onRemoveLocation(it)
            },
            onDismiss = { removingLocation = false },
        )
    }
}

private val EditMessage.textRes: Int
    get() = when (this) {
        EditMessage.LocationCopied -> R.string.edit_location_copied
        EditMessage.OtherPhotoNoLocation -> R.string.edit_location_copy_none
        EditMessage.OtherPhotoLocationRemoved -> R.string.edit_location_copy_removed
        EditMessage.OtherPhotoUnreadable -> R.string.edit_location_copy_failed
        EditMessage.PlannerUnavailable -> R.string.edit_review_planner_unavailable
    }

/** Room under the last item for the "Review changes" button. */
private val FAB_CLEARANCE = 112.dp

private const val GENERIC_MIME_TYPE = "application/octet-stream"
