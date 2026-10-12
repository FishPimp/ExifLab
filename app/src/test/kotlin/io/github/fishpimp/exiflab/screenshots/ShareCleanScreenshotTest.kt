package io.github.fishpimp.exiflab.screenshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.shareclean.CleanedFile
import io.github.fishpimp.exiflab.data.shareclean.MetadataKind
import io.github.fishpimp.exiflab.data.shareclean.Refusal
import io.github.fishpimp.exiflab.data.shareclean.RefusalReason
import io.github.fishpimp.exiflab.data.shareclean.ShareCleanChoice
import io.github.fishpimp.exiflab.data.shareclean.ShareCleanPreset
import io.github.fishpimp.exiflab.data.shareclean.ShareCleanResult
import io.github.fishpimp.exiflab.designsystem.theme.BrandPalette
import io.github.fishpimp.exiflab.designsystem.theme.ThemeMode
import io.github.fishpimp.exiflab.metadata.edit.StripCategory
import io.github.fishpimp.exiflab.ui.home.HomeContent
import io.github.fishpimp.exiflab.ui.photo.PhotoActions
import io.github.fishpimp.exiflab.ui.photo.PhotoContent
import io.github.fishpimp.exiflab.ui.photo.PhotoLoad
import io.github.fishpimp.exiflab.ui.photo.PhotoUiState
import io.github.fishpimp.exiflab.ui.photo.TagBrowserBuilder
import io.github.fishpimp.exiflab.ui.photo.ValueMode
import io.github.fishpimp.exiflab.ui.selection.SelectionContent
import io.github.fishpimp.exiflab.ui.shareclean.ShareCleanActions
import io.github.fishpimp.exiflab.ui.shareclean.ShareCleanSheetContent
import io.github.fishpimp.exiflab.ui.shareclean.ShareCleanStep
import io.github.fishpimp.exiflab.ui.shareclean.ShareCleanUiState
import io.github.fishpimp.exiflab.ui.shareclean.SummarySource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Share clean: the preset sheet in its states, the compact "Clean and share" sheet and its entry points. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareCleanScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val single = ShareCleanUiState(
        photos = listOf(FakeReports.rawRef.copy(displayName = "DSCF4410.JPG", mimeType = "image/jpeg")),
        choice = ShareCleanChoice(ShareCleanPreset.LocationAndDevice),
        summary = SummarySource.Ready(FakeReports.cameraRaw),
    )

    private val several: ShareCleanUiState = run {
        val photos = FakePhotos.listing.photos.take(12)
        ShareCleanUiState(
            photos = photos,
            choice = ShareCleanChoice(ShareCleanPreset.Everything),
            summary = SummarySource.Several,
            rawCount = photos.count { it.displayName?.substringAfterLast('.')?.uppercase() in setOf("RAF", "CR3", "NEF", "DNG", "ARW") }
                .coerceAtLeast(2),
        )
    }

    @Test @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun single_photo() = compose.snapshot("shareclean_single_light") { Sheet(single) }

    @Test @Config(qualifiers = "w411dp-h1300dp-night-xxhdpi")
    fun single_phone_dark() = compose.snapshot("shareclean_single_phone_dark", ThemeMode.Dark, BrandPalette.Iris) {
        Sheet(
            ShareCleanUiState(
                photos = listOf(FakeReports.phoneRef),
                choice = ShareCleanChoice(ShareCleanPreset.LocationOnly),
                summary = SummarySource.Ready(FakeReports.phoneJpeg),
            ),
        )
    }

    @Test @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun single_raw() = compose.snapshot("shareclean_single_raw", palette = BrandPalette.Moss) {
        Sheet(single.copy(photos = listOf(FakeReports.rawRef), rawCount = 1))
    }

    @Test @Config(qualifiers = "w411dp-h1300dp-night-xxhdpi")
    fun several_photos_dark() = compose.snapshot("shareclean_several_dark", ThemeMode.Dark, BrandPalette.Ember) { Sheet(several) }

    @Test @Config(qualifiers = "sv-w411dp-h1900dp-xxhdpi")
    fun custom_expanded_swedish() = compose.snapshot("shareclean_custom_sv", palette = BrandPalette.Citrus) {
        Sheet(single.copy(choice = ShareCleanChoice(ShareCleanPreset.Custom, custom = setOf(StripCategory.Location, StripCategory.PersonNames))))
    }

    @Test @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun custom_empty() = compose.snapshot("shareclean_custom_empty", palette = BrandPalette.Moss) {
        Sheet(several.copy(choice = ShareCleanChoice(ShareCleanPreset.Custom, custom = emptySet()), rawCount = 0), compact = true)
    }

    @Test @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun too_many() = compose.snapshot("shareclean_too_many", palette = BrandPalette.Graphite) {
        val photos = List(140) { FakePhotos.photo("IMG_$it.jpg") }
        Sheet(several.copy(photos = photos, rawCount = 0, choice = ShareCleanChoice(ShareCleanPreset.LocationOnly)))
    }

    @Test @Config(qualifiers = "w411dp-h2600dp-xxhdpi", fontScale = 2.0f)
    fun single_large_font() = compose.snapshot("shareclean_single_font200") { Sheet(single) }

    @Test @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun clean_and_share_compact() = compose.snapshot("shareclean_compact_light", palette = BrandPalette.Iris) {
        Sheet(several.copy(choice = ShareCleanChoice(ShareCleanPreset.LocationAndDevice), photos = several.photos.take(3), rawCount = 0), compact = true)
    }

    @Test @Config(qualifiers = "sv-w411dp-h1100dp-night-xxhdpi")
    fun clean_and_share_compact_swedish_dark() = compose.snapshot("shareclean_compact_sv_dark", ThemeMode.Dark) {
        Sheet(single.copy(choice = ShareCleanChoice(ShareCleanPreset.LocationOnly)), compact = true)
    }

    @Test @Config(qualifiers = "w411dp-h700dp-xxhdpi")
    fun cleaning() = compose.snapshot("shareclean_cleaning") { Sheet(several.copy(step = ShareCleanStep.Cleaning(done = 4, total = 12))) }

    @Test @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun finished_with_refusals() = compose.snapshot("shareclean_finished", palette = BrandPalette.Graphite) {
        Sheet(several.copy(step = ShareCleanStep.Finished(finished)))
    }

    @Test @Config(qualifiers = "sv-w411dp-h1700dp-xxhdpi", fontScale = 2.0f)
    fun finished_swedish_large_font() = compose.snapshot("shareclean_finished_sv_font200") {
        Sheet(several.copy(step = ShareCleanStep.Finished(finished)))
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun viewer_top_bar() = compose.snapshot("shareclean_viewer_action") {
        WithFakeThumbnails {
            val report = FakeReports.phoneJpeg
            PhotoContent(
                ref = FakeReports.phoneRef,
                state = PhotoUiState(
                    load = PhotoLoad.Loaded(report),
                    browser = TagBrowserBuilder.build(report, "", ValueMode.Readable, null, TagBrowserBuilder.defaultCollapsed(report)),
                ),
                query = "",
                actions = PhotoActions(),
                onShareClean = {},
            )
        }
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun selection_toolbar() = compose.snapshot("shareclean_selection_toolbar", palette = BrandPalette.Citrus) {
        WithFakeThumbnails {
            val refs = FakePhotos.listing.photos.take(9)
            SelectionContent(
                refs = refs,
                onBack = {},
                onOpenPhoto = {},
                selection = FakeBatch.folderSelection,
                onShareClean = {},
            )
        }
    }

    @Test @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun home_light() = compose.snapshot("shareclean_home_light") { Home() }

    @Test @Config(qualifiers = "sv-w411dp-h1500dp-night-xxhdpi")
    fun home_swedish_dark() = compose.snapshot("shareclean_home_sv_dark", ThemeMode.Dark, BrandPalette.Moss) { Home() }

    @Test @Config(qualifiers = "w411dp-h3000dp-xxhdpi", fontScale = 2.0f)
    fun home_large_font() = compose.snapshot("shareclean_home_font200") { Home() }

    private val finished = ShareCleanResult(
        files = List(9) { CleanedFile(File("IMG_$it.jpg"), "image/jpeg", "IMG_$it.jpg") },
        refusals = listOf(
            Refusal("DSCF4410.RAF", RefusalReason.NoRawPreview),
            Refusal("scan_004.tif", RefusalReason.Unsupported("TIFF")),
            Refusal("PXL_20260614_174207123.jpg", RefusalReason.StillContains(listOf(MetadataKind.Location, MetadataKind.SerialNumbers))),
        ),
    )

    @Composable
    private fun Home() = WithFakeThumbnails {
        HomeContent(
            recents = emptyList<PhotoRef>(),
            isOpening = false,
            onOpenPhotoPicker = {},
            onOpenFiles = {},
            onBrowseFolders = {},
            onOpenRecent = {},
            onOpenPrivacy = {},
        )
    }

    /** Looks like the open bottom sheet: rounded top, sheet color, a drag handle. */
    @Composable
    private fun Sheet(state: ShareCleanUiState, compact: Boolean = false) = WithFakeThumbnails {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                ShareCleanSheetContent(state = state, actions = ShareCleanActions(), compact = compact)
            }
        }
    }
}
