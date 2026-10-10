package io.github.fishpimp.exiflab.ui.photo

import android.content.ClipData
import android.content.ClipDescription
import android.content.res.Resources
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalResources
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.MetadataTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Puts metadata on the clipboard and confirms it with a snackbar. Privacy-sensitive values are
 * flagged so the system clipboard preview hides them (Android 13+).
 */
@Stable
class ClipboardCopier internal constructor(
    private val clipboard: Clipboard,
    private val snackbarHostState: SnackbarHostState,
    private val scope: CoroutineScope,
    private val resources: Resources,
) {
    /** Copies the shown value of [tag]. */
    fun copyValue(tag: MetadataTag, value: String) =
        copy(tag.name, value, sensitive = tag.sensitivity != null, resources.getString(R.string.photo_copied_value, tag.name))

    /** Copies "Name: value" for [tag]. */
    fun copyNameAndValue(tag: MetadataTag, value: String) = copy(
        tag.name,
        ReportText.nameAndValue(tag, value),
        sensitive = tag.sensitivity != null,
        resources.getString(R.string.photo_copied_value, tag.name),
    )

    /** Copies coordinates, which are always sensitive. */
    fun copyCoordinates(text: String) =
        copy(resources.getString(R.string.photo_location_title), text, sensitive = true, resources.getString(R.string.photo_copied_coordinates))

    /** Copies every tag of [report] as tab-separated text; the text is built off the main thread. */
    fun copyAll(report: MetadataReport) {
        val header = resources.getString(R.string.photo_copy_all_header)
        val message = resources.getQuantityString(R.plurals.photo_copied_all, report.tagCount, report.tagCount)
        val label = report.fileName ?: resources.getString(R.string.photo_untitled)
        scope.launch {
            val text = withContext(Dispatchers.Default) { ReportText.tabSeparated(report, header) }
            // The full dump holds whatever the file holds, location and serials included.
            write(label, text, sensitive = report.sensitiveFindings.isNotEmpty())
            confirm(message)
        }
    }

    private fun copy(label: String, text: String, sensitive: Boolean, message: String) {
        scope.launch {
            write(label, text, sensitive)
            confirm(message)
        }
    }

    private suspend fun write(label: String, text: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText(label, text)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        clipboard.setClipEntry(ClipEntry(clip))
    }

    private suspend fun confirm(message: String) {
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
    }
}

@Composable
fun rememberClipboardCopier(snackbarHostState: SnackbarHostState): ClipboardCopier {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    return remember(clipboard, snackbarHostState, scope, resources) {
        ClipboardCopier(clipboard, snackbarHostState, scope, resources)
    }
}
