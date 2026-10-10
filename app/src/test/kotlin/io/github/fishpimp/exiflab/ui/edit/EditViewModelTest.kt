package io.github.fishpimp.exiflab.ui.edit

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.history.EditMode
import io.github.fishpimp.exiflab.data.history.SaveError
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.places.Place
import io.github.fishpimp.exiflab.data.places.PlaceKind
import io.github.fishpimp.exiflab.data.places.PlaceSearchState
import io.github.fishpimp.exiflab.data.save.SaveOutcome
import io.github.fishpimp.exiflab.data.save.SaveRejection
import io.github.fishpimp.exiflab.data.save.SaveTarget
import io.github.fishpimp.exiflab.data.save.SavedEditNotices
import io.github.fishpimp.exiflab.metadata.edit.ChangePlanner
import io.github.fishpimp.exiflab.metadata.edit.EditOperation
import io.github.fishpimp.exiflab.metadata.edit.FieldDiff
import io.github.fishpimp.exiflab.metadata.edit.TagCatalog
import io.github.fishpimp.exiflab.metadata.edit.ValidationError
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.screenshots.FakeCatalog
import io.github.fishpimp.exiflab.screenshots.FakePlanner
import io.github.fishpimp.exiflab.screenshots.FakeReports
import io.github.fishpimp.exiflab.screenshots.FakeSaving
import io.github.fishpimp.exiflab.ui.map.LatLng
import io.github.fishpimp.exiflab.ui.photo.PhotoLoadError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class EditViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val notices = SavedEditNotices()
    private val saving = FakeSaving()
    private val planner = FakePlanner()
    private val copyRef = PhotoRef("content://docs/copy.jpg", "PXL_edited.jpg", "image/jpeg", 1L, null, PhotoOrigin.Document, writable = true)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        ref: PhotoRef = FakeReports.phoneRef,
        report: MetadataReport = FakeReports.phoneJpeg,
        read: suspend (PhotoRef) -> MetadataReport = { report },
        planner: () -> ChangePlanner = { this.planner },
        catalog: () -> TagCatalog = { FakeCatalog() },
        readOther: suspend (String) -> MetadataReport = { FakeReports.phoneJpeg },
        search: (Flow<String>) -> Flow<PlaceSearchState> = { queries -> queries.map { if (it.isBlank()) PlaceSearchState.Idle else PlaceSearchState.Results(it, places) } },
        compute: kotlinx.coroutines.CoroutineDispatcher = dispatcher,
    ) = EditViewModel(
        ref = ref,
        readReport = read,
        planner = planner,
        catalog = catalog,
        saving = saving,
        notices = notices,
        resolveCopy = { uri -> copyRef.copy(uri = uri) },
        readOtherPhoto = readOther,
        searchPlaces = search,
        computeDispatcher = compute,
    )

    private val places = listOf(Place("Gamla stan", "Stockholm", null, "Sweden", LatLng(59.3251, 18.0711), PlaceKind.District))

    private fun TestScope.observe(vm: EditViewModel): () -> EditUiState {
        backgroundScope.launch { vm.state.collect { } }
        return { vm.state.value }
    }

    private fun TestScope.events(vm: EditViewModel): List<EditEvent> {
        val list = mutableListOf<EditEvent>()
        backgroundScope.launch { vm.events.collect { list += it } }
        return list
    }

    @Test
    fun `loads the report and starts with nothing queued`() = runTest(dispatcher) {
        val state = observe(viewModel())
        assertThat(state().report).isEqualTo(FakeReports.phoneJpeg)
        assertThat(state().edits).isEmpty()
        assertThat(state().plan).isEqualTo(PlanState.Empty)
        assertThat(state().saveOptions).isEqualTo(SaveOptions(listOf(SaveMode.InPlace, SaveMode.Copy), SaveReason.Writable))
        assertThat(state().canSave).isFalse()
    }

    @Test
    fun `a read failure is classified`() = runTest(dispatcher) {
        val state = observe(viewModel(read = { throw FileNotFoundException() }))
        assertThat(state().load).isEqualTo(EditLoad.Failed(PhotoLoadError.NotFound))
        val retry = observe(viewModel(read = { throw IOException("busy") }))
        assertThat((retry().load as EditLoad.Failed).error.canRetry).isTrue()
    }

    @Test
    fun `queued operations are planned and the plan follows the queue`() = runTest(dispatcher) {
        val vm = viewModel()
        val state = observe(vm)
        vm.setLocation(48.8584, 2.2945, 35.0)
        vm.setAuthorship("Maja Lindqvist", "")

        assertThat(state().edits.map { it.operation }).containsExactly(
            EditOperation.SetLocation(48.8584, 2.2945, 35.0),
            EditOperation.SetAuthorship("Maja Lindqvist", null),
        ).inOrder()
        val plan = (state().plan as PlanState.Ready).plan
        assertThat(plan.diff.map { it.label }).containsAtLeast("GPS Latitude", "GPS Longitude", "GPS Altitude", "Artist")
        assertThat(planner.calls.last()).hasSize(2)
        assertThat(state().canSave).isTrue()

        vm.removeEdit(state().edits.first().id)
        assertThat((state().plan as PlanState.Ready).plan.diff.map { it.label }).containsExactly("Artist", "dc:creator")

        vm.discardEdits()
        assertThat(state().plan).isEqualTo(PlanState.Empty)
    }

    @Test
    fun `planning runs on the compute dispatcher and shows progress until done`() = runTest(dispatcher) {
        val compute = StandardTestDispatcher(testScheduler)
        val vm = viewModel(compute = compute)
        val state = observe(vm)
        advanceUntilIdle()
        vm.shiftTime(Duration.ofHours(1))
        assertThat(state().plan).isEqualTo(PlanState.Planning)
        advanceUntilIdle()
        assertThat(state().plan).isInstanceOf(PlanState.Ready::class.java)
    }

    @Test
    fun `edits in the same slot replace each other, shifts add up and a set time drops other time edits`() = runTest(dispatcher) {
        val vm = viewModel()
        val state = observe(vm)
        vm.shiftTime(Duration.ofHours(1))
        vm.shiftTime(Duration.ofMinutes(30))
        assertThat(state().edits.map { it.operation }).containsExactly(EditOperation.ShiftCaptureTime(Duration.ofMinutes(90)))

        vm.shiftTime(Duration.ofMinutes(-90))
        assertThat(state().edits).isEmpty()

        vm.setUtcOffset(ZoneOffset.ofHours(1))
        vm.changeTimeZone(ZoneId.of("Europe/Stockholm"), ZoneId.of("America/New_York"))
        vm.shiftTime(Duration.ofSeconds(5))
        vm.setLocation(1.0, 2.0, null)
        vm.removeLocation(includePlaceNames = true)
        assertThat(state().edits.map { it.slot }).containsExactly(
            EditSlot.UtcOffset, EditSlot.TimeZone, EditSlot.TimeShift, EditSlot.Location,
        ).inOrder()
        assertThat(state().edits.last().operation).isEqualTo(EditOperation.RemoveLocation(true))

        val local = LocalDateTime.of(2026, 1, 2, 3, 4, 5)
        vm.setCaptureTime(local, null, alsoModifyDate = true)
        assertThat(state().edits.map { it.operation }).containsExactly(
            EditOperation.RemoveLocation(true),
            EditOperation.SetCaptureTime(local, null, true),
        ).inOrder()
    }

    @Test
    fun `the date card previews the queued time edits`() = runTest(dispatcher) {
        val vm = viewModel()
        val state = observe(vm)
        vm.shiftTime(Duration.ofHours(2))
        vm.changeTimeZone(ZoneId.of("Europe/Stockholm"), ZoneId.of("Asia/Tokyo"))
        val after = FakeReports.phoneJpeg.captureTimeAfter(state().edits)
        // 19:42 + 2 h = 21:42 in Stockholm (UTC+2) is 04:42 the next day in Tokyo (UTC+9).
        assertThat(after.local).isEqualTo(LocalDateTime.of(2026, 6, 15, 4, 42, 7, 123_000_000))
        assertThat(after.offset).isEqualTo(ZoneOffset.ofHours(9))
    }

    @Test
    fun `a missing planner or catalog shows as unavailable instead of crashing`() = runTest(dispatcher) {
        val vm = viewModel(planner = { TODO("planner") }, catalog = { TODO("catalog") })
        val state = observe(vm)
        assertThat(state().fields).isEqualTo(FieldsState.Unavailable)
        vm.setLocation(1.0, 2.0, null)
        assertThat(state().plan).isEqualTo(PlanState.Unavailable)
        assertThat(state().canSave).isFalse()
        vm.save(SaveMode.InPlace)
        assertThat(state().save).isEqualTo(SaveState.Failed(SaveFailure.NoPlan))
        assertThat(saving.saves).isEmpty()
    }

    @Test
    fun `a planner error is shown, not thrown`() = runTest(dispatcher) {
        val failing = object : ChangePlanner {
            override fun plan(report: MetadataReport, operations: List<EditOperation>) = throw IllegalArgumentException("No capture date to shift")
        }
        val vm = viewModel(planner = { failing })
        val state = observe(vm)
        vm.shiftTime(Duration.ofHours(1))
        assertThat(state().plan).isEqualTo(PlanState.Failed("No capture date to shift"))
    }

    @Test
    fun `all fields lists common fields first, then present fields by group, and offers the absent ones`() = runTest(dispatcher) {
        val vm = viewModel()
        val state = observe(vm)
        val fields = state().fields as FieldsState.Ready
        assertThat(fields.sections.first().kind).isEqualTo(FieldSectionKind.Common)
        assertThat(fields.sections.first().rows.map { it.field.label }).containsExactly("Title", "Image Description", "Artist", "Copyright", "Keywords", "Rating").inOrder()
        val exif = fields.sections.single { it.kind == FieldSectionKind.Group(io.github.fishpimp.exiflab.metadata.model.DirectoryGroup.Exif) }
        assertThat(exif.rows.map { it.field.label }).containsAtLeast("F-Number", "Make", "Model", "Orientation")
        assertThat(exif.rows.first { it.field.label == "Make" }.current).isEqualTo("Google")
        assertThat(fields.absent.map { it.label }).containsAtLeast("City", "Country", "Body Serial Number", "Headline")

        vm.onFieldQueryChange("model")
        val searched = state().fields as FieldsState.Ready
        assertThat(searched.searching).isTrue()
        assertThat(searched.sections.flatMap { it.rows }.map { it.field.label }).containsExactly("Model", "Lens Model")

        vm.onFieldQueryChange("")
        vm.addField("iptc:2:90")
        val added = state().fields as FieldsState.Ready
        assertThat(added.sections.flatMap { it.rows }.map { it.field.label }).contains("City")
        assertThat(added.absent.map { it.label }).doesNotContain("City")
        assertThat(state().fieldEditor).isEqualTo(FieldEditor("iptc:2:90", ""))
    }

    @Test
    fun `field values are validated inline and queue as operations`() = runTest(dispatcher) {
        val vm = viewModel()
        val state = observe(vm)
        vm.editField("exif:exif:0x829D")
        assertThat(state().fieldEditor).isEqualTo(FieldEditor("exif:exif:0x829D", "168/100"))

        vm.onFieldDraftChange("f/2.8")
        assertThat(state().fieldEditor!!.error).isEqualTo(ValidationError.NotANumber)
        vm.onFieldDraftChange("")
        assertThat(state().fieldEditor!!.error).isNull()
        assertThat(vm.applyField()).isFalse()
        assertThat(state().fieldEditor!!.error).isEqualTo(ValidationError.Empty)
        vm.onFieldDraftChange("99")
        assertThat(vm.applyField()).isFalse()
        assertThat(state().fieldEditor!!.error).isEqualTo(ValidationError.OutOfRange)

        vm.onFieldDraftChange("2.8")
        assertThat(vm.applyField()).isTrue()
        assertThat(state().fieldEditor).isNull()
        val edit = state().edits.single()
        assertThat(edit.slot).isEqualTo(EditSlot.Field("exif:exif:0x829D"))
        assertThat(edit.fieldLabel).isEqualTo("F-Number")
        assertThat(edit.fieldValue).isEqualTo("2.8")
        val row = (state().fields as FieldsState.Ready).sections.flatMap { it.rows }.single { it.field.id == "exif:exif:0x829D" }
        assertThat(row.pending).isEqualTo(PendingFieldValue("2.8"))
        assertThat(row.shown).isEqualTo("2.8")
        assertThat((state().plan as PlanState.Ready).plan.diff.single()).isEqualTo(FieldDiff("F-Number", io.github.fishpimp.exiflab.metadata.model.DirectoryGroup.Exif, "168/100", "2.8"))

        // Editing again starts from the queued value; removing replaces it; reverting drops it.
        vm.editField("exif:exif:0x829D")
        assertThat(state().fieldEditor!!.draft).isEqualTo("2.8")
        vm.removeField("exif:exif:0x829D")
        assertThat(state().edits.single().fieldValue).isNull()
        assertThat(state().fieldEditor).isNull()
        vm.revertField("exif:exif:0x829D")
        assertThat(state().edits).isEmpty()
    }

    @Test
    fun `keyword lists are normalized before validation`() = runTest(dispatcher) {
        val vm = viewModel()
        val state = observe(vm)
        vm.editField("xmp:dc:subject")
        vm.onFieldDraftChange(" midsummer ;  archipelago,,sunset ")
        assertThat(vm.applyField()).isTrue()
        assertThat(state().edits.single().fieldValue).isEqualTo("midsummer, archipelago, sunset")
    }

    @Test
    fun `a writable JPEG saves in place, tells the viewer and leaves`() = runTest(dispatcher) {
        val vm = viewModel()
        val state = observe(vm)
        val events = events(vm)
        vm.setAuthorship("Maja", "© Maja")
        vm.save(SaveMode.InPlace)

        assertThat(saving.saves).containsExactly(SaveTarget.InPlace)
        assertThat(events).containsExactly(EditEvent.Finished(openCopy = null))
        assertThat(state().edits).isEmpty()
        assertThat(state().save).isEqualTo(SaveState.Idle)
        val notice = notices.forPhoto(FakeReports.phoneRef.uri).first()!!
        assertThat(notice.mode).isEqualTo(EditMode.InPlace)
        assertThat(notice.canUndo).isTrue()
    }

    @Test
    fun `a read-only picker photo is saved as a copy, which then opens`() = runTest(dispatcher) {
        val vm = viewModel(ref = FakeReports.phonePickerRef, report = FakeReports.phoneJpegRedacted)
        val state = observe(vm)
        val events = events(vm)
        assertThat(state().saveOptions).isEqualTo(SaveOptions(listOf(SaveMode.Copy), SaveReason.ReadOnly, suggestOpenOriginal = true))

        vm.setLocation(59.3293, 18.0686, null)
        vm.save(SaveMode.Copy)
        val ask = events.single() as EditEvent.ChooseDestination
        assertThat(ask.mode).isEqualTo(SaveMode.Copy)
        assertThat(ask.suggestedName).isEqualTo("PXL_20260614_174207123_edited.jpg")
        assertThat(ask.mimeType).isEqualTo("image/jpeg")
        assertThat(state().save).isEqualTo(SaveState.ChoosingDestination(SaveMode.Copy))
        assertThat(saving.saves).isEmpty()

        vm.onDestinationChosen(SaveMode.Copy, "content://docs/new-copy")
        assertThat(saving.saves).containsExactly(SaveTarget.Copy("content://docs/new-copy"))
        assertThat(events.last()).isEqualTo(EditEvent.Finished(copyRef.copy(uri = "content://docs/new-copy")))
        val notice = notices.forPhoto("content://docs/new-copy").first()!!
        assertThat(notice.canUndo).isFalse()
        assertThat(notices.forPhoto(FakeReports.phonePickerRef.uri).first()).isNull()
    }

    @Test
    fun `cancelling the copy destination goes back to idle with the edits kept`() = runTest(dispatcher) {
        val vm = viewModel(ref = FakeReports.phonePickerRef)
        val state = observe(vm)
        vm.setLocation(1.0, 2.0, null)
        vm.save(SaveMode.Copy)
        vm.onDestinationChosen(SaveMode.Copy, null)
        assertThat(state().save).isEqualTo(SaveState.Idle)
        assertThat(state().edits).hasSize(1)
    }

    @Test
    fun `RAW goes to a sidecar, asking for a place when the folder is unknown`() = runTest(dispatcher) {
        saving.outcome = { _, target ->
            when (target) {
                is SaveTarget.Sidecar -> if (target.destinationUri == null) SaveOutcome.NeedsSidecarDestination("DSCF4410.xmp") else SaveOutcome.Saved("r1", EditMode.Sidecar, target.destinationUri!!)
                else -> error("unexpected $target")
            }
        }
        val vm = viewModel(ref = FakeReports.rawRef, report = FakeReports.cameraRaw)
        val state = observe(vm)
        val events = events(vm)
        assertThat(state().saveOptions).isEqualTo(SaveOptions(listOf(SaveMode.Sidecar), SaveReason.Raw))

        vm.setUtcOffset(ZoneOffset.ofHours(2))
        vm.save(SaveMode.Sidecar)
        assertThat(saving.saves).containsExactly(SaveTarget.Sidecar(null))
        assertThat(events.single()).isEqualTo(EditEvent.ChooseDestination(SaveMode.Sidecar, "DSCF4410.xmp", "application/octet-stream"))

        vm.onDestinationChosen(SaveMode.Sidecar, null)
        assertThat(state().save).isEqualTo(SaveState.Failed(SaveFailure.NoDestination))

        vm.save(SaveMode.Sidecar)
        vm.onDestinationChosen(SaveMode.Sidecar, "content://docs/DSCF4410.xmp")
        assertThat(saving.saves.last()).isEqualTo(SaveTarget.Sidecar("content://docs/DSCF4410.xmp"))
        assertThat(events.last()).isEqualTo(EditEvent.Finished(null))
        assertThat(notices.forPhoto(FakeReports.rawRef.uri).first()!!.mode).isEqualTo(EditMode.Sidecar)
    }

    @Test
    fun `formats without a writer go to a sidecar too`() = runTest(dispatcher) {
        val heic = FakeReports.phoneRef.copy(uri = "content://x/IMG_1.HEIC", displayName = "IMG_1.HEIC", mimeType = "image/heic")
        val state = observe(viewModel(ref = heic, report = FakeReports.phoneJpeg.copy(format = io.github.fishpimp.exiflab.metadata.ImageFormat.Heif)))
        assertThat(state().saveOptions).isEqualTo(SaveOptions(listOf(SaveMode.Sidecar), SaveReason.UnsupportedFormat))
    }

    @Test
    fun `a rejected in-place save switches the offer to what the file allows`() = runTest(dispatcher) {
        saving.outcome = { _, _ -> SaveOutcome.Rejected(SaveRejection.ReadOnly) }
        val vm = viewModel()
        val state = observe(vm)
        vm.setLocation(1.0, 2.0, null)
        vm.save(SaveMode.InPlace)
        assertThat(state().save).isEqualTo(SaveState.Failed(SaveFailure.Rejected(SaveRejection.ReadOnly)))
        assertThat(state().saveOptions!!.modes).containsExactly(SaveMode.Copy)
        assertThat(state().edits).hasSize(1)
        vm.dismissSaveFailure()
        assertThat(state().save).isEqualTo(SaveState.Idle)
    }

    @Test
    fun `failed and rolled back saves keep the edits and say what happened`() = runTest(dispatcher) {
        saving.outcome = { _, _ -> SaveOutcome.RolledBack("r1", SaveError.VerificationFailed) }
        val vm = viewModel()
        val state = observe(vm)
        vm.setLocation(1.0, 2.0, null)
        vm.save(SaveMode.InPlace)
        assertThat(state().save).isEqualTo(SaveState.Failed(SaveFailure.Error(SaveError.VerificationFailed, rolledBack = true)))
        assertThat(state().edits).hasSize(1)

        saving.outcome = { _, _ -> throw IllegalStateException("boom") }
        vm.save(SaveMode.InPlace)
        assertThat(state().save).isEqualTo(SaveState.Failed(SaveFailure.Error(SaveError.Unknown, rolledBack = false)))
        // Changing the queue clears the old failure.
        vm.setLocation(3.0, 4.0, null)
        assertThat(state().save).isEqualTo(SaveState.Idle)
    }

    @Test
    fun `skipped changes are shown before returning to the viewer`() = runTest(dispatcher) {
        saving.outcome = { ref, _ -> SaveOutcome.Saved("r1", EditMode.InPlace, ref.uri, skipped = listOf("IPTC City: PNG has no IPTC")) }
        val vm = viewModel()
        val state = observe(vm)
        val events = events(vm)
        vm.setLocation(1.0, 2.0, null)
        vm.save(SaveMode.InPlace)
        assertThat(state().save).isEqualTo(SaveState.SavedWithSkipped(EditMode.InPlace, listOf("IPTC City: PNG has no IPTC"), null))
        assertThat(events).isEmpty()
        assertThat(notices.forPhoto(FakeReports.phoneRef.uri).first()!!.skipped).isEqualTo(1)
        vm.finishAfterSave()
        assertThat(events).containsExactly(EditEvent.Finished(null))
        assertThat(state().save).isEqualTo(SaveState.Idle)
    }

    @Test
    fun `copying the location from another photo`() = runTest(dispatcher) {
        var other = FakeReports.phoneJpeg
        val vm = viewModel(ref = FakeReports.rawRef, report = FakeReports.cameraRaw, readOther = { other })
        val state = observe(vm)
        val events = events(vm)

        vm.copyLocationFrom("content://media/picker/1")
        val location = FakeReports.phoneJpeg.location!!
        assertThat(state().edits.single().operation).isEqualTo(EditOperation.SetLocation(location.latitude, location.longitude, location.altitudeMeters))
        assertThat(events.last()).isEqualTo(EditEvent.Message(EditMessage.LocationCopied))

        other = FakeReports.phoneJpegRedacted
        vm.copyLocationFrom("content://media/picker/2")
        assertThat(events.last()).isEqualTo(EditEvent.Message(EditMessage.OtherPhotoLocationRemoved))

        other = FakeReports.barePng
        vm.copyLocationFrom("content://media/picker/3")
        assertThat(events.last()).isEqualTo(EditEvent.Message(EditMessage.OtherPhotoNoLocation))

        val failing = viewModel(readOther = { throw IOException("gone") })
        val failingEvents = events(failing)
        failing.copyLocationFrom("content://media/picker/4")
        assertThat(failingEvents.single()).isEqualTo(EditEvent.Message(EditMessage.OtherPhotoUnreadable))
        assertThat(state().readingOtherPhoto).isFalse()
    }

    @Test
    fun `pages open and close, and the location picker searches places`() = runTest(dispatcher) {
        val vm = viewModel()
        val state = observe(vm)
        vm.openPage(EditorPage.Location)
        assertThat(state().page).isEqualTo(EditorPage.Location)
        vm.onPlaceQueryChange("gamla")
        assertThat(vm.placeQuery).isEqualTo("gamla")
        assertThat(state().placeSearch).isEqualTo(PlaceSearchState.Results("gamla", places))

        vm.setLocation(59.3251, 18.0711, null)
        assertThat(state().page).isEqualTo(EditorPage.Overview)

        vm.openPage(EditorPage.TimeZone)
        vm.changeTimeZone(ZoneId.of("Europe/Stockholm"), ZoneId.of("Europe/London"))
        assertThat(state().page).isEqualTo(EditorPage.Overview)
        vm.openPage(EditorPage.Review)
        vm.closePage()
        assertThat(state().page).isEqualTo(EditorPage.Overview)
    }
}
