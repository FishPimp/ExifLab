package io.github.fishpimp.exiflab.ui.export

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.export.ExportProgress
import io.github.fishpimp.exiflab.data.export.ExportStage
import io.github.fishpimp.exiflab.data.export.pdf.PdfPalette
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import kotlinx.coroutines.launch

/**
 * Everything an export needs on screen, for any screen that offers one: the options sheet, the
 * system "Save as" step, the progress dialog and the snackbars that report the result (with
 * "Open" to view the file). Screens call [ExportViewModel.open] to start.
 */
@Composable
fun ExportHost(
    snackbarHostState: SnackbarHostState,
    viewModel: ExportViewModel = viewModel(key = EXPORT_VIEW_MODEL_KEY, factory = ExportViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current

    // The report is printed on paper: always light, in the app's current colors.
    val scheme = MaterialTheme.colorScheme
    val isDark = ExifLabTheme.isDark
    val sensitive = ExifLabTheme.extendedColors.sensitive
    val palette = remember(scheme, isDark, sensitive) { PdfPalette.from(scheme, isDark, sensitive) }
    val currentPalette by rememberUpdatedState(palette)

    val mimeType = state.settings.format.mimeType
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
        viewModel.onDestinationChosen(uri, currentPalette)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ExportEvent.ChooseDestination -> try {
                    createDocument.launch(event.fileName)
                } catch (_: ActivityNotFoundException) {
                    launch { snackbarHostState.showSnackbar(resources.getString(R.string.home_no_picker)) }
                }
                is ExportEvent.Finished -> launch {
                    val result = snackbarHostState.showSnackbar(
                        message = finishedMessage(resources, event),
                        actionLabel = resources.getString(R.string.export_open),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed && !openExport(context, event)) {
                        snackbarHostState.showSnackbar(resources.getString(R.string.export_open_failed))
                    }
                }
                is ExportEvent.Failed -> launch {
                    snackbarHostState.showSnackbar(errorMessage(resources, event), duration = SnackbarDuration.Long)
                }
                ExportEvent.Cancelled -> launch { snackbarHostState.showSnackbar(resources.getString(R.string.export_cancelled)) }
            }
        }
    }

    val request = state.request
    if (request != null && state.sheetOpen && state.progress == null) {
        ExportSheet(
            request = request,
            settings = state.settings,
            fileName = viewModel.suggestedFileName(state).orEmpty(),
            mapOffline = state.mapOffline,
            onSettingsChange = viewModel::updateSettings,
            onExport = viewModel::confirm,
            onDismiss = viewModel::dismiss,
        )
    }
    state.progress?.let { progress ->
        ExportProgressDialog(progress = progress, single = request?.isSingle != false, onCancel = viewModel::cancel)
    }
}

/** The running export: an expressive loading indicator, what is happening, a wavy progress bar and Cancel. */
@Composable
fun ExportProgressDialog(progress: ExportProgress, single: Boolean, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        icon = { ContainedLoadingIndicator() },
        title = { Text(stringResource(R.string.export_running_title)) },
        text = { ExportProgressContent(progress, single) },
        confirmButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** The dialog's body, stateless for screenshot tests. */
@Composable
fun ExportProgressContent(progress: ExportProgress, single: Boolean, modifier: Modifier = Modifier) {
    val status = when {
        single -> stringResource(R.string.export_running_single)
        progress.stage == ExportStage.Preparing -> stringResource(R.string.export_running_preparing, progress.done, progress.total)
        else -> stringResource(R.string.export_running_photo, minOf(progress.done + 1, progress.total), progress.total)
    }
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            status,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (single) {
            LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        } else {
            LinearWavyProgressIndicator(progress = { progress.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }
    }
}

private fun finishedMessage(resources: Resources, event: ExportEvent.Finished): String = when {
    event.failed > 0 -> resources.getQuantityString(R.plurals.export_done_skipped, event.failed, event.failed)
    event.single -> resources.getString(R.string.export_done_single)
    else -> resources.getQuantityString(R.plurals.export_done, event.exported, event.exported)
}

private fun errorMessage(resources: Resources, event: ExportEvent.Failed): String = resources.getString(
    when (event.error) {
        ExportError.Write -> R.string.export_error_write
        ExportError.Pdf -> R.string.export_error_pdf
        ExportError.Unreadable -> if (event.single) R.string.export_error_unreadable_single else R.string.export_error_unreadable_all
    },
)

/** Opens the exported file in an app that can show it, with read access to just that file. */
private fun openExport(context: Context, event: ExportEvent.Finished): Boolean {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(event.uri, event.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private const val EXPORT_VIEW_MODEL_KEY = "metadata-export"
