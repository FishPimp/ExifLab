package io.github.fishpimp.exiflab.screenshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.export.ExportFormat
import io.github.fishpimp.exiflab.data.export.ExportProgress
import io.github.fishpimp.exiflab.data.export.ExportSettings
import io.github.fishpimp.exiflab.data.export.ExportStage
import io.github.fishpimp.exiflab.designsystem.theme.BrandPalette
import io.github.fishpimp.exiflab.designsystem.theme.ThemeMode
import io.github.fishpimp.exiflab.ui.export.ExportProgressContent
import io.github.fishpimp.exiflab.ui.export.ExportRequest
import io.github.fishpimp.exiflab.ui.export.ExportSheetContent
import io.github.fishpimp.exiflab.ui.export.ExportViewModel
import io.github.fishpimp.exiflab.ui.folder.FolderContent
import io.github.fishpimp.exiflab.ui.folder.FolderUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** The export sheet (one photo and a batch), its progress dialog and the batch toolbar with Export. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val single = ExportRequest(listOf(FakeReports.phoneRef))
    private val batch = ExportRequest(FakePhotos.recents, folderName = "Camera")

    @Test @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun sheet_single_pdf_light() = compose.snapshot("export_sheet_single_light") {
        Sheet(single, ExportSettings.defaultFor(1))
    }

    @Test @Config(qualifiers = "w411dp-h1100dp-night-xxhdpi")
    fun sheet_single_pdf_dark_offline() = compose.snapshot("export_sheet_single_dark", ThemeMode.Dark, BrandPalette.Iris) {
        Sheet(single, ExportSettings.defaultFor(1), mapOffline = true)
    }

    @Test @Config(qualifiers = "sv-w411dp-h1100dp-xxhdpi")
    fun sheet_single_redacted_swedish() = compose.snapshot("export_sheet_single_sv", palette = BrandPalette.Citrus) {
        Sheet(single, ExportSettings(format = ExportFormat.Pdf, redactSensitive = true))
    }

    @Test @Config(qualifiers = "w411dp-h2400dp-xxhdpi", fontScale = 2.0f)
    fun sheet_single_large_font() = compose.snapshot("export_sheet_single_font200") {
        Sheet(single, ExportSettings.defaultFor(1))
    }

    @Test @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun sheet_batch_csv_light() = compose.snapshot("export_sheet_batch_light", palette = BrandPalette.Moss) {
        Sheet(batch, ExportSettings.defaultFor(batch.photos.size))
    }

    @Test @Config(qualifiers = "w411dp-h1100dp-night-xxhdpi")
    fun sheet_batch_json_dark() = compose.snapshot("export_sheet_batch_dark", ThemeMode.Dark, BrandPalette.Ember) {
        Sheet(batch, ExportSettings(format = ExportFormat.Json, includeRawValues = false))
    }

    @Test @Config(qualifiers = "sv-w411dp-h1100dp-night-xxhdpi")
    fun sheet_batch_swedish_dark() = compose.snapshot("export_sheet_batch_sv_dark", ThemeMode.Dark) {
        Sheet(batch, ExportSettings.defaultFor(batch.photos.size))
    }

    @Test @Config(qualifiers = "w411dp-h2600dp-xxhdpi", fontScale = 2.0f)
    fun sheet_batch_large_font() = compose.snapshot("export_sheet_batch_font200") {
        Sheet(batch, ExportSettings.defaultFor(batch.photos.size))
    }

    @Test @Config(qualifiers = "w411dp-h500dp-xxhdpi")
    fun progress_batch() = compose.snapshot("export_progress_batch") {
        Progress(ExportProgress(7, 12, ExportStage.Writing, 0.6f), single = false)
    }

    @Test @Config(qualifiers = "sv-w411dp-h700dp-night-xxhdpi", fontScale = 2.0f)
    fun progress_preparing_swedish_large_font() = compose.snapshot("export_progress_sv_font200", ThemeMode.Dark) {
        Progress(ExportProgress(3, 12, ExportStage.Preparing, 0.04f), single = false)
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun batch_toolbar_with_export() = compose.snapshot("export_batch_toolbar") {
        WithFakeThumbnails {
            FolderContent(
                title = "Camera",
                state = FolderUiState.Loaded(FakePhotos.listing),
                onBack = {},
                onOpenSubfolder = {},
                onOpenPhoto = {},
                selection = FakeBatch.folderSelection,
                onExport = {},
            )
        }
    }

    @Composable
    private fun Sheet(request: ExportRequest, settings: ExportSettings, mapOffline: Boolean = false) {
        SheetFrame {
            ExportSheetContent(
                request = request,
                settings = settings,
                fileName = ExportViewModel.suggestedFileName(request, settings.format, LocalDate.of(2026, 10, 12)),
                mapOffline = mapOffline,
                onSettingsChange = {},
                onExport = {},
                onCancel = {},
            )
        }
    }

    /** Looks like the open bottom sheet: rounded top, sheet color, a drag handle's room. */
    @Composable
    private fun SheetFrame(content: @Composable () -> Unit) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                content()
            }
        }
    }

    /** The dialog's card, as AlertDialog draws it, around the progress body. */
    @Composable
    private fun Progress(progress: ExportProgress, single: Boolean) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    stringResource(R.string.export_running_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                ExportProgressContent(progress, single)
            }
        }
    }
}
