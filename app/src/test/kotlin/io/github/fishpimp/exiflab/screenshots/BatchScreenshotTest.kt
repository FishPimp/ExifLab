package io.github.fishpimp.exiflab.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.fishpimp.exiflab.data.batch.BatchItemStatus
import io.github.fishpimp.exiflab.designsystem.theme.BrandPalette
import io.github.fishpimp.exiflab.designsystem.theme.ThemeMode
import io.github.fishpimp.exiflab.ui.batch.BatchEditActions
import io.github.fishpimp.exiflab.ui.batch.BatchEditContent
import io.github.fishpimp.exiflab.ui.batch.BatchEditUiState
import io.github.fishpimp.exiflab.ui.batch.BatchRunActions
import io.github.fishpimp.exiflab.ui.batch.BatchRunContent
import io.github.fishpimp.exiflab.ui.batch.BatchRunUiState
import io.github.fishpimp.exiflab.ui.batch.CopyFolder
import io.github.fishpimp.exiflab.ui.folder.FolderContent
import io.github.fishpimp.exiflab.ui.folder.FolderUiState
import io.github.fishpimp.exiflab.ui.selection.SelectionContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Batch editing: folder grid in selection mode with the toolbar, the strip editor, the preview
 * with mixed file kinds and warnings, progress and the summary; light, dark, Swedish, 200% font
 * and tablet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatchScreenshotTest {
    @get:Rule val compose = createComposeRule()

    // ---- Selection ----

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun selection_folder_light() = compose.snapshot("batch_select_folder_light") { FolderSelection() }

    @Test @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun selection_folder_dark() = compose.snapshot("batch_select_folder_dark", ThemeMode.Dark, BrandPalette.Iris) { FolderSelection() }

    @Test @Config(qualifiers = "sv-w411dp-h891dp-xxhdpi")
    fun selection_folder_swedish() = compose.snapshot("batch_select_folder_sv", palette = BrandPalette.Citrus) { FolderSelection() }

    @Test @Config(qualifiers = "w411dp-h1800dp-xxhdpi", fontScale = 2.0f)
    fun selection_folder_large_font() = compose.snapshot("batch_select_folder_font200") { FolderSelection() }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun selection_folder_tablet() = compose.snapshot("batch_select_folder_tablet", palette = BrandPalette.Moss) { FolderSelection() }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun selection_picked() = compose.snapshot("batch_select_picked") {
        WithFakeThumbnails {
            SelectionContent(
                refs = FakePhotos.recents,
                onBack = {},
                onOpenPhoto = {},
                selection = FakeBatch.folderSelection.copy(selected = FakePhotos.recents.take(2).map { it.uri }.toSet()),
            )
        }
    }

    // ---- Editor and preview ----

    @Test @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun strip_light() = compose.snapshot("batch_strip_light") { Editor(FakeBatch.strip) }

    @Test @Config(qualifiers = "sv-w411dp-h1600dp-night-xxhdpi")
    fun strip_swedish_dark() = compose.snapshot("batch_strip_sv_dark", ThemeMode.Dark, BrandPalette.Ember) { Editor(FakeBatch.strip) }

    @Test @Config(qualifiers = "w411dp-h2800dp-xxhdpi", fontScale = 2.0f)
    fun strip_large_font() = compose.snapshot("batch_strip_font200") { Editor(FakeBatch.strip) }

    @Test @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun preview_light() = compose.snapshot("batch_preview_light") { Editor(FakeBatch.preview) }

    @Test @Config(qualifiers = "w411dp-h2000dp-night-xxhdpi")
    fun preview_dark_with_folder() = compose.snapshot("batch_preview_dark_folder", ThemeMode.Dark, BrandPalette.Iris) {
        Editor(FakeBatch.preview.copy(copyFolder = CopyFolder("content://fake/tree/out", "Edited copies")))
    }

    @Test @Config(qualifiers = "sv-w411dp-h2000dp-xxhdpi")
    fun preview_swedish() = compose.snapshot("batch_preview_sv", palette = BrandPalette.Citrus) { Editor(FakeBatch.shiftPreview) }

    @Test @Config(qualifiers = "w411dp-h4000dp-xxhdpi", fontScale = 2.0f)
    fun preview_large_font() = compose.snapshot("batch_preview_font200") { Editor(FakeBatch.preview) }

    @Test @Config(qualifiers = "w1280dp-h1000dp-land-xhdpi")
    fun preview_tablet() = compose.snapshot("batch_preview_tablet", palette = BrandPalette.Moss) { Editor(FakeBatch.shiftPreview) }

    // ---- Progress and summary ----

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun progress_light() = compose.snapshot("batch_progress_light") { Run(FakeBatch.progress) }

    @Test @Config(qualifiers = "sv-w411dp-h891dp-night-xxhdpi")
    fun progress_swedish_dark() = compose.snapshot("batch_progress_sv_dark", ThemeMode.Dark, BrandPalette.Iris) { Run(FakeBatch.progress) }

    @Test @Config(qualifiers = "w411dp-h1800dp-xxhdpi", fontScale = 2.0f)
    fun progress_large_font() = compose.snapshot("batch_progress_font200") { Run(FakeBatch.progress) }

    @Test @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun summary_light() = compose.snapshot("batch_summary_light") { Run(FakeBatch.summary) }

    @Test @Config(qualifiers = "w411dp-h1500dp-night-xxhdpi")
    fun summary_dark_filtered() = compose.snapshot("batch_summary_dark_filtered", ThemeMode.Dark, BrandPalette.Ember) {
        Run(FakeBatch.summary.copy(filter = BatchItemStatus.Sidecar))
    }

    @Test @Config(qualifiers = "sv-w411dp-h1500dp-xxhdpi")
    fun summary_swedish() = compose.snapshot("batch_summary_sv", palette = BrandPalette.Citrus) { Run(FakeBatch.summary) }

    @Test @Config(qualifiers = "w411dp-h3600dp-xxhdpi", fontScale = 2.0f)
    fun summary_large_font() = compose.snapshot("batch_summary_font200") { Run(FakeBatch.summary) }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun summary_tablet() = compose.snapshot("batch_summary_tablet", palette = BrandPalette.Moss) { Run(FakeBatch.summary) }

    @Composable
    private fun FolderSelection() = WithFakeThumbnails {
        FolderContent(
            title = "Camera",
            state = FolderUiState.Loaded(FakePhotos.listing),
            onBack = {},
            onOpenSubfolder = {},
            onOpenPhoto = {},
            selection = FakeBatch.folderSelection,
        )
    }

    @Composable
    private fun Editor(state: BatchEditUiState) = WithFakeThumbnails {
        BatchEditContent(state = state, artist = "", copyright = "", placeQuery = "", actions = BatchEditActions())
    }

    @Composable
    private fun Run(state: BatchRunUiState) = WithFakeThumbnails {
        BatchRunContent(state = state, actions = BatchRunActions())
    }
}
