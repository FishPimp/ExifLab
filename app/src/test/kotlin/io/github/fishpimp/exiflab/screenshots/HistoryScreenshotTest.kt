package io.github.fishpimp.exiflab.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.data.history.EditRecord
import io.github.fishpimp.exiflab.data.settings.AppSettings
import io.github.fishpimp.exiflab.data.settings.BackupRetention
import io.github.fishpimp.exiflab.designsystem.theme.BrandPalette
import io.github.fishpimp.exiflab.designsystem.theme.ThemeMode
import io.github.fishpimp.exiflab.ui.history.HistoryContent
import io.github.fishpimp.exiflab.ui.history.HistoryDetailActions
import io.github.fishpimp.exiflab.ui.history.HistoryDetailBody
import io.github.fishpimp.exiflab.ui.history.HistoryDetailContent
import io.github.fishpimp.exiflab.ui.history.HistoryUiState
import io.github.fishpimp.exiflab.ui.settings.BackupUsage
import io.github.fishpimp.exiflab.ui.settings.editingAndBackupSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** History list, batch, detail and the Backups settings, in light, dark, Swedish, 200% font and on a tablet. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoryScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun empty() = compose.snapshot("history_empty") { List(FakeHistory.empty) }

    @Test @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun list_light() = compose.snapshot("history_list_light") { List(expandBatch = true) }

    @Test @Config(qualifiers = "w411dp-h2000dp-night-xxhdpi")
    fun list_dark() = compose.snapshot("history_list_dark", ThemeMode.Dark, BrandPalette.Iris) { List(expandBatch = true) }

    @Test @Config(qualifiers = "sv-w411dp-h2000dp-xxhdpi")
    fun list_swedish() = compose.snapshot("history_list_sv", palette = BrandPalette.Citrus) { List(expandBatch = true) }

    @Test @Config(qualifiers = "w411dp-h4200dp-xxhdpi", fontScale = 2.0f)
    fun list_large_font() = compose.snapshot("history_list_font200") { List(expandBatch = true) }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun tablet_list_detail() = compose.snapshot("history_tablet_list_detail", palette = BrandPalette.Moss) {
        WithFakeThumbnails {
            HistoryContent(
                state = FakeHistory.list,
                onOpenRecord = {},
                onRestoreBatch = {},
                selectedRecordId = FakeHistory.inPlace.id,
                detailPane = { modifier ->
                    HistoryDetailBody(FakeHistory.detail(), HistoryDetailActions(), modifier)
                },
            )
        }
    }

    @Test @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    fun detail_light() = compose.snapshot("history_detail_light") { Detail(FakeHistory.inPlace) }

    @Test @Config(qualifiers = "sv-w411dp-h1900dp-night-xxhdpi")
    fun detail_swedish_dark() = compose.snapshot("history_detail_sv_dark", ThemeMode.Dark, BrandPalette.Ember) {
        Detail(FakeHistory.inPlace)
    }

    @Test @Config(qualifiers = "w411dp-h3800dp-xxhdpi", fontScale = 2.0f)
    fun detail_large_font() = compose.snapshot("history_detail_font200") { Detail(FakeHistory.inPlace) }

    @Test @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    fun detail_damaged() = compose.snapshot("history_detail_damaged") { Detail(FakeHistory.damaged) }

    @Test @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun detail_sidecar() = compose.snapshot("history_detail_sidecar", palette = BrandPalette.Graphite) {
        Detail(FakeHistory.batchRecords.first())
    }

    @Test @Config(qualifiers = "w411dp-h1400dp-night-xxhdpi")
    fun detail_rolled_back_dark() = compose.snapshot("history_detail_rolled_back_dark", ThemeMode.Dark) {
        Detail(FakeHistory.rolledBack)
    }

    @Test @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun detail_restore() = compose.snapshot("history_detail_restore") { Detail(FakeHistory.restore) }

    @Test @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun backups_settings() = compose.snapshot("settings_backups_light") { Backups() }

    @Test @Config(qualifiers = "sv-w411dp-h1300dp-night-xxhdpi")
    fun backups_settings_swedish_dark() = compose.snapshot("settings_backups_sv_dark", ThemeMode.Dark, BrandPalette.Iris) {
        Backups(retention = BackupRetention.Forever)
    }

    @Test @Config(qualifiers = "w411dp-h2600dp-xxhdpi", fontScale = 2.0f)
    fun backups_settings_large_font() = compose.snapshot("settings_backups_font200") { Backups() }

    @Composable
    private fun List(state: HistoryUiState = FakeHistory.list, expandBatch: Boolean = false) = WithFakeThumbnails {
        HistoryContent(
            state = state,
            onOpenRecord = {},
            onRestoreBatch = {},
            initiallyExpandedBatches = if (expandBatch) setOf(FakeHistory.batch.id) else emptySet(),
        )
    }

    @Composable
    private fun Detail(record: EditRecord) = WithFakeThumbnails {
        HistoryDetailContent(state = FakeHistory.detail(record), actions = HistoryDetailActions(), onBack = {})
    }

    @Composable
    private fun Backups(retention: BackupRetention = BackupRetention.Days30) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            contentPadding = PaddingValues(16.dp),
        ) {
            editingAndBackupSettings(
                settings = AppSettings(backupRetention = retention),
                backups = BackupUsage(usedBytes = 486_539_264),
                onBackupRetention = {},
                onSidecarNaming = {},
                onDeleteAllBackups = {},
            )
        }
    }
}
