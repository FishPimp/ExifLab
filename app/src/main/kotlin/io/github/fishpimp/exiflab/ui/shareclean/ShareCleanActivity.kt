package io.github.fishpimp.exiflab.ui.shareclean

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.appGraph
import io.github.fishpimp.exiflab.data.photos.MAX_PHOTOS_PER_OPEN
import io.github.fishpimp.exiflab.data.photos.sharedStreamUris
import io.github.fishpimp.exiflab.data.settings.AppSettings
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.ui.components.messageRes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * "Clean and share": a second share-sheet entry, and the target of the "Share clean" launcher
 * shortcut. It shows only the compact Share clean sheet over the app the user came from (its
 * window is translucent), cleans the photos and opens the system share sheet again so the user
 * can pick where they really go. Finishing returns to the app that shared them.
 *
 * Shared photos arrive with [Intent.ACTION_SEND] or [Intent.ACTION_SEND_MULTIPLE]; the shortcut
 * sends [ACTION_PICK_AND_SHARE], which opens the photo picker first.
 */
class ShareCleanActivity : AppCompatActivity() {
    private val viewModel: ShareCleanViewModel by viewModels { ShareCleanViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val picks = intent.action == ACTION_PICK_AND_SHARE
        // The view model outlives configuration changes but not process death; after that the
        // shared photos are opened again from the intent, which still carries their grants.
        if (!picks && !viewModel.startedFromIntent) {
            viewModel.startedFromIntent = true
            val uris = intent.sharedStreamUris()
            if (uris.isEmpty()) {
                finish()
                return
            }
            viewModel.openUris(uris)
        }

        val settingsState: StateFlow<AppSettings?> = appGraph.settingsRepository.settings
            .stateIn(lifecycleScope, SharingStarted.Eagerly, null)
        setContent {
            val settings by settingsState.collectAsStateWithLifecycle()
            val current = settings ?: return@setContent
            ExifLabTheme(
                themeMode = current.themeMode,
                dynamicColor = current.dynamicColor,
                palette = current.palette,
                contrast = current.contrast,
            ) {
                if (picks) {
                    val pickPhotos = rememberLauncherForActivityResult(
                        ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS_PER_OPEN),
                    ) { uris -> if (uris.isEmpty()) finish() else viewModel.openUris(uris) }
                    var pickerShown by rememberSaveable { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        if (pickerShown) return@LaunchedEffect
                        pickerShown = true
                        try {
                            pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        } catch (_: ActivityNotFoundException) {
                            showMessage(R.string.home_no_picker)
                            finish()
                        }
                    }
                }
                ShareCleanSheetHost(
                    viewModel = viewModel,
                    compact = true,
                    onShared = ::finish,
                    onClosed = ::finish,
                    onOpenFailed = { error ->
                        showMessage(error.messageRes)
                        finish()
                    },
                )
            }
        }
    }

    private fun showMessage(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        /** The launcher shortcut's action: pick photos, then clean and share them. */
        const val ACTION_PICK_AND_SHARE = "io.github.fishpimp.exiflab.action.SHARE_CLEAN"
    }
}
