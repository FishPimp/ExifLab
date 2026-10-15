package io.github.fishpimp.exiflab.screenshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.data.history.EditMode
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.places.Place
import io.github.fishpimp.exiflab.data.places.PlaceKind
import io.github.fishpimp.exiflab.data.places.PlaceSearchError
import io.github.fishpimp.exiflab.data.places.PlaceSearchState
import io.github.fishpimp.exiflab.data.save.SavedEditNotices
import io.github.fishpimp.exiflab.designsystem.theme.BrandPalette
import io.github.fishpimp.exiflab.designsystem.theme.ThemeMode
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.edit.AuthorField
import io.github.fishpimp.exiflab.metadata.edit.PlanWarning
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.write.WriteSkip
import io.github.fishpimp.exiflab.ui.edit.EditActions
import io.github.fishpimp.exiflab.ui.edit.EditContent
import io.github.fishpimp.exiflab.ui.edit.EditUiState
import io.github.fishpimp.exiflab.ui.edit.EditViewModel
import io.github.fishpimp.exiflab.ui.edit.EditorPage
import io.github.fishpimp.exiflab.ui.edit.SaveMode
import io.github.fishpimp.exiflab.ui.edit.SaveState
import io.github.fishpimp.exiflab.ui.edit.location.LocationDraft
import io.github.fishpimp.exiflab.ui.edit.location.LocationPickerContent
import io.github.fishpimp.exiflab.ui.edit.time.ShiftTimeContent
import io.github.fishpimp.exiflab.ui.edit.time.TimeShift
import io.github.fishpimp.exiflab.ui.edit.time.TimeZoneWizardContent
import io.github.fishpimp.exiflab.ui.edit.time.TimeZoneWizardState
import io.github.fishpimp.exiflab.ui.edit.time.WizardStep
import io.github.fishpimp.exiflab.ui.edit.time.ZoneCatalog
import io.github.fishpimp.exiflab.ui.map.LatLng
import io.github.fishpimp.exiflab.ui.map.LocalMapAvailabilityOverride
import io.github.fishpimp.exiflab.ui.map.MapAvailability
import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The metadata editor with fake planner, catalog and saver: the overview in several setups, its
 * sheets and pages, and the review for each way a file can be saved.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val stockholm = ZoneId.of("Europe/Stockholm")
    private val places = listOf(
        Place("Gamla stan", "Stockholm", "Stockholm County", "Sweden", LatLng(59.32517, 18.07110), PlaceKind.District),
        Place("Gamla Uppsala", "Uppsala", "Uppsala County", "Sweden", LatLng(59.89806, 17.63611), PlaceKind.City),
        Place("Stortorget", "Stockholm", "Stockholm County", "Sweden", LatLng(59.32503, 18.07069), PlaceKind.Street),
        Place("Nobel Prize Museum", "Stockholm", "Stockholm County", "Sweden", LatLng(59.32505, 18.07073), PlaceKind.PointOfInterest),
    )

    private fun viewModel(
        ref: PhotoRef,
        report: MetadataReport,
        warnings: List<PlanWarning> = emptyList(),
        setUp: EditViewModel.() -> Unit = {},
    ): EditViewModel = EditViewModel(
        ref = ref,
        readReport = { report },
        planner = { FakePlanner(warnings = warnings) },
        catalog = { FakeCatalog() },
        saving = FakeSaving(),
        notices = SavedEditNotices(),
        resolveCopy = { ref },
        readOtherPhoto = { report },
        searchPlaces = { queries -> queries.map { if (it.isBlank()) PlaceSearchState.Idle else PlaceSearchState.Results(it, places) } },
        computeDispatcher = Dispatchers.Main.immediate,
    ).apply(setUp)

    /** A typical session: a time shift, a new place, the author, and a title. */
    private val typicalEdits: EditViewModel.() -> Unit = {
        shiftTime(Duration.ofHours(-1))
        setLocation(59.32517, 18.07110, 22.0)
        setAuthorship("Maja Lindqvist", "© 2026 Maja Lindqvist")
        editField("xmp:dc:title")
        onFieldDraftChange("Midsummer on Stortorget")
        applyField()
    }

    @Test @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun overview_phone_light() = compose.snapshot("edit_overview_phone_light") {
        Editor(viewModel(FakeReports.phoneRef, FakeReports.phoneJpeg, setUp = typicalEdits))
    }

    @Test @Config(qualifiers = "w411dp-h2600dp-night-xxhdpi")
    fun overview_phone_dark() = compose.snapshot("edit_overview_phone_dark", ThemeMode.Dark, BrandPalette.Iris) {
        Editor(viewModel(FakeReports.phoneRef, FakeReports.phoneJpeg, setUp = typicalEdits))
    }

    @Test @Config(qualifiers = "sv-w411dp-h2600dp-xxhdpi")
    fun overview_swedish() = compose.snapshot("edit_overview_sv", palette = BrandPalette.Citrus) {
        Editor(viewModel(FakeReports.rawRef, FakeReports.cameraRaw) { setUtcOffset(ZoneOffset.ofHours(9)) })
    }

    @Test @Config(qualifiers = "w411dp-h4800dp-xxhdpi", fontScale = 2.0f)
    fun overview_large_font() = compose.snapshot("edit_overview_font200") {
        Editor(viewModel(FakeReports.phoneRef, FakeReports.phoneJpeg, setUp = typicalEdits))
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun overview_tablet() = compose.snapshot("edit_overview_tablet", palette = BrandPalette.Moss) {
        Editor(viewModel(FakeReports.phoneRef, FakeReports.phoneJpeg, setUp = typicalEdits))
    }

    @Test @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun all_fields_validation_error() = compose.snapshot("edit_fields_error") {
        val vm = viewModel(FakeReports.phoneRef, FakeReports.phoneJpeg) {
            editField("xmp:dc:subject")
            onFieldDraftChange("midsummer, stockholm")
            applyField()
            editField("exif:exif:0x829D")
            onFieldDraftChange("f/2.8")
        }
        Editor(vm, firstItem = 1)
    }

    @Test @Config(qualifiers = "w411dp-h1200dp-xxhdpi")
    fun shift_time_sheet() = compose.snapshot("edit_shift_sheet") {
        SheetFrame {
            ShiftTimeContent(
                shift = TimeShift(hours = 1, minutes = 30, later = false),
                capturedAt = FakeReports.phoneJpeg.summary.capturedAt,
                offset = FakeReports.phoneJpeg.summary.utcOffset,
                onShiftChange = {},
                onApply = {},
                onCancel = {},
            )
        }
    }

    @Test @Config(qualifiers = "sv-w411dp-h1100dp-night-xxhdpi")
    fun shift_time_sheet_swedish_dark() = compose.snapshot("edit_shift_sheet_sv_dark", ThemeMode.Dark, BrandPalette.Ember) {
        SheetFrame {
            ShiftTimeContent(
                shift = TimeShift(days = 2, seconds = 15),
                capturedAt = FakeReports.cameraRaw.summary.capturedAt,
                offset = null,
                onShiftChange = {},
                onApply = {},
                onCancel = {},
            )
        }
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun time_zone_wizard() = compose.snapshot("edit_tz_wizard") {
        TimeZone(WizardStep.ActualZone, actual = ZoneId.of("America/New_York"), query = "")
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun time_zone_wizard_search() = compose.snapshot("edit_tz_wizard_search", palette = BrandPalette.Iris) {
        TimeZone(WizardStep.CameraZone, actual = null, query = "new")
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun location_picker_results() = compose.snapshot("edit_location_results") {
        LocationPicker(query = "gamla", search = PlaceSearchState.Results("gamla", places), availability = MapAvailability.Preview)
    }

    @Test @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun location_picker_offline() = compose.snapshot("edit_location_offline", ThemeMode.Dark) {
        LocationPicker(
            query = "gamla stan",
            search = PlaceSearchState.Failed("gamla stan", PlaceSearchError.Offline),
            availability = MapAvailability.OfflineMode,
            coordinates = "59°19'30.6\"N 18°4'16\"E",
        )
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun location_picker_invalid() = compose.snapshot("edit_location_invalid", palette = BrandPalette.Citrus) {
        LocationPicker(query = "", search = PlaceSearchState.Idle, availability = MapAvailability.Preview, coordinates = "https://maps.app.goo.gl/x1Y2", pin = null)
    }

    @Test @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun review_jpeg_in_place() = compose.snapshot("edit_review_jpeg") {
        Editor(
            viewModel(
                FakeReports.phoneRef,
                FakeReports.phoneJpeg,
                warnings = listOf(PlanWarning.NonAsciiField(AuthorField.Artist, alsoIptc = true)),
                setUp = { typicalEdits(); openPage(EditorPage.Review) },
            ),
        )
    }

    @Test @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun review_read_only_copy() = compose.snapshot("edit_review_copy", palette = BrandPalette.Iris) {
        Editor(
            viewModel(FakeReports.phonePickerRef, FakeReports.phoneJpegRedacted) {
                setLocation(59.32930, 18.06860, null)
                openPage(EditorPage.Review)
            },
        )
    }

    @Test @Config(qualifiers = "w411dp-h1600dp-night-xxhdpi")
    fun review_raw_sidecar() = compose.snapshot("edit_review_raw", ThemeMode.Dark, BrandPalette.Moss) {
        Editor(
            viewModel(FakeReports.rawRef, FakeReports.cameraRaw) {
                changeTimeZone(stockholm, ZoneId.of("Asia/Tokyo"))
                removeLocation(includePlaceNames = true)
                openPage(EditorPage.Review)
            },
        )
    }

    @Test @Config(qualifiers = "sv-w411dp-h1200dp-xxhdpi")
    fun review_saving_swedish() = compose.snapshot("edit_review_saving_sv", palette = BrandPalette.Ember) {
        Editor(
            viewModel(FakeReports.phoneRef, FakeReports.phoneJpeg) {
                setAuthorship("Maja Lindqvist", "")
                openPage(EditorPage.Review)
            },
            override = { it.copy(save = SaveState.Saving(SaveMode.InPlace)) },
        )
    }

    @Test @Config(qualifiers = "w411dp-h1200dp-xxhdpi")
    fun review_saved_with_skipped() = compose.snapshot("edit_review_skipped") {
        Editor(
            viewModel(FakeReports.pngRef, FakeReports.barePng) { openPage(EditorPage.Review) },
            override = {
                it.copy(save = SaveState.SavedWithSkipped(EditMode.Copy, listOf(WriteSkip.IptcUnsupported(ImageFormat.Png, 2), WriteSkip.MakerNoteMoved), null))
            },
        )
    }

    @Composable
    private fun Editor(vm: EditViewModel, firstItem: Int = 0, override: (EditUiState) -> EditUiState = { it }) = WithEditorPreview {
        val state by vm.state.collectAsState()
        EditContent(
            ref = vm.ref,
            state = override(state),
            fieldQuery = vm.fieldQuery,
            fieldDraft = vm.fieldDraft,
            placeQuery = vm.placeQuery,
            actions = EditActions(),
            listState = rememberLazyListState(initialFirstVisibleItemIndex = firstItem),
        )
    }

    @Composable
    private fun TimeZone(step: WizardStep, actual: ZoneId?, query: String) = WithEditorPreview {
        val recorded = FakeReports.phoneJpeg.summary.capturedAt!!.withNano(0)
        val locale = LocalLocale.current.platformLocale
        val zones = remember(locale) { ZoneCatalog.all(recorded, locale) }
        TimeZoneWizardContent(
            state = TimeZoneWizardState(step, stockholm, actual, query),
            recorded = recorded,
            zones = zones,
            deviceZone = stockholm,
            // As if the GPS time stamp put the photo at UTC-04:00.
            suggestedZone = ZoneCatalog.suggestFor(ZoneOffset.ofHours(-4), recorded, stockholm, zones),
            onStateChange = {},
            onApply = { _, _ -> },
            onClose = {},
        )
    }

    @Composable
    private fun LocationPicker(
        query: String,
        search: PlaceSearchState,
        availability: MapAvailability,
        coordinates: String = "59.325170, 18.071100",
        pin: LatLng? = LatLng(59.32517, 18.07110),
    ) = WithEditorPreview(availability) {
        LocationPickerContent(
            draft = LocationDraft(pin, coordinates, "22"),
            query = query,
            search = search,
            onQueryChange = {},
            onPlacePicked = {},
            onMapPick = {},
            onCoordinatesChange = {},
            onAltitudeChange = {},
            onConfirm = {},
            onClose = {},
        )
    }

    /** Looks like the open bottom sheet: rounded top, sheet color, a drag handle's room. */
    @Composable
    private fun SheetFrame(content: @Composable () -> Unit) = WithEditorPreview {
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

    @Composable
    private fun WithEditorPreview(availability: MapAvailability = MapAvailability.Preview, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalMapAvailabilityOverride provides availability, content = content)
    }
}
