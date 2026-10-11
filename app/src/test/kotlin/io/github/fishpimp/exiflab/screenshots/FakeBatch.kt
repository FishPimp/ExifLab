package io.github.fishpimp.exiflab.screenshots

import io.github.fishpimp.exiflab.data.batch.BatchItemReason
import io.github.fishpimp.exiflab.data.batch.BatchItemStatus
import io.github.fishpimp.exiflab.data.batch.BatchJob
import io.github.fishpimp.exiflab.data.batch.BatchJobItem
import io.github.fishpimp.exiflab.data.batch.BatchJobState
import io.github.fishpimp.exiflab.data.batch.BatchKind
import io.github.fishpimp.exiflab.data.batch.BatchOperation
import io.github.fishpimp.exiflab.data.history.SaveError
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.metadata.edit.FieldDiff
import io.github.fishpimp.exiflab.metadata.edit.StripCategory
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.GeoLocation
import io.github.fishpimp.exiflab.ui.batch.BatchDraft
import io.github.fishpimp.exiflab.ui.batch.BatchEditUiState
import io.github.fishpimp.exiflab.ui.batch.BatchEditorPage
import io.github.fishpimp.exiflab.ui.batch.BatchPhotos
import io.github.fishpimp.exiflab.ui.batch.BatchPreview
import io.github.fishpimp.exiflab.ui.batch.BatchRunUiState
import io.github.fishpimp.exiflab.ui.batch.SamplePlan
import io.github.fishpimp.exiflab.ui.batch.WarningCount
import io.github.fishpimp.exiflab.ui.edit.time.TimeShift
import io.github.fishpimp.exiflab.ui.selection.PhotoSelection

/** Batch editor, progress and summary states for screenshot tests. */
internal object FakeBatch {
    private val photos = FakePhotos.listing.photos + listOf(
        FakePhotos.photo("IMG_3001.jpg", writable = false, origin = PhotoOrigin.Picker),
        FakePhotos.photo("IMG_3002.jpg", writable = false, origin = PhotoOrigin.Picker),
    )

    /** A folder grid in selection mode with a handful of mixed photos selected. */
    val folderSelection = PhotoSelection(
        active = true,
        selected = FakePhotos.listing.photos.filterIndexed { index, _ -> index in setOf(0, 2, 3, 5, 6) }.map { it.uri }.toSet(),
    )

    val strip = BatchEditUiState(
        kind = BatchKind.Strip,
        photos = BatchPhotos.Loaded(photos),
        draft = BatchDraft(strip = setOf(StripCategory.Location, StripCategory.DeviceIdentifiers, StripCategory.MakerNotes)),
    )

    private val stockholm = GeoLocation(59.32938, 18.06871, null)

    val preview = BatchEditUiState(
        kind = BatchKind.Location,
        photos = BatchPhotos.Loaded(List(48) { index -> photos[index % photos.size].copy(uri = "content://fake/$index") }),
        page = BatchEditorPage.Preview,
        draft = BatchDraft(location = stockholm),
        preview = BatchPreview(
            total = 48,
            checked = 31,
            inPlace = 29,
            sidecars = 9,
            sidecarsNeedingFolder = 1,
            copiesNeedingFolder = 6,
            skipped = 0,
            unchanged = 2,
            unreadable = 1,
            warnings = listOf(
                WarningCount(WarningCount.Kind.Other, 3, "The latitude 91.2 is outside -90 to 90 degrees; the location was not changed"),
            ),
            sample = SamplePlan(
                FakePhotos.photo("PXL_20260912_081455.jpg"),
                listOf(
                    FieldDiff("GPS Latitude", DirectoryGroup.Gps, null, "59° 19' 45.77\" N"),
                    FieldDiff("GPS Longitude", DirectoryGroup.Gps, null, "18° 4' 7.36\" E"),
                    FieldDiff("exif:GPSLatitude", DirectoryGroup.Xmp, null, "59,19.7628N"),
                    FieldDiff("exif:GPSLongitude", DirectoryGroup.Xmp, null, "18,4.1226E"),
                ),
            ),
        ),
    )

    /** A time shift where some photos have no date. */
    val shiftPreview = preview.copy(
        kind = BatchKind.ShiftTime,
        draft = BatchDraft(shift = TimeShift(hours = 1)),
        preview = preview.preview!!.copy(
            checked = 48,
            unchanged = 12,
            inPlace = 19,
            unreadable = 0,
            warnings = listOf(WarningCount(WarningCount.Kind.NoCaptureDate, 12)),
            sample = SamplePlan(
                FakePhotos.photo("PXL_20260912_081455.jpg"),
                listOf(
                    FieldDiff("Date/Time Original", DirectoryGroup.Exif, "2026:09:12 08:14:55", "2026:09:12 09:14:55"),
                    FieldDiff("Create Date", DirectoryGroup.Exif, "2026:09:12 08:14:55", "2026:09:12 09:14:55"),
                ),
            ),
        ),
    )

    private val job = BatchJob(
        id = "job",
        state = BatchJobState.Running,
        operationJson = BatchOperation.encode(BatchOperation.SetLocation(59.32938, 18.06871)),
        title = "Set location",
        total = 48,
        createdAt = 0,
        editBatchId = "batch",
    )

    val progress = BatchRunUiState(
        isLoading = false,
        job = job,
        counts = mapOf(
            BatchItemStatus.Saved to 17,
            BatchItemStatus.Sidecar to 3,
            BatchItemStatus.Skipped to 2,
            BatchItemStatus.Failed to 1,
            BatchItemStatus.Running to 2,
            BatchItemStatus.Pending to 23,
        ),
        runningNames = listOf("PXL_20260913_190012.jpg", "DSCF4411.RAF"),
    )

    private fun item(position: Int, name: String, status: BatchItemStatus, reason: BatchItemReason? = null, error: SaveError? = null): BatchJobItem {
        val ref = FakePhotos.photo(name, writable = status != BatchItemStatus.Copy)
        return BatchJobItem.of("job", position, ref).copy(status = status, reason = reason, saveError = error)
    }

    val summary = BatchRunUiState(
        isLoading = false,
        job = job.copy(state = BatchJobState.Finished, total = 8),
        counts = mapOf(
            BatchItemStatus.Saved to 3,
            BatchItemStatus.Sidecar to 2,
            BatchItemStatus.Copy to 1,
            BatchItemStatus.Skipped to 1,
            BatchItemStatus.Failed to 1,
        ),
        items = listOf(
            item(0, "PXL_20260912_081455.jpg", BatchItemStatus.Saved),
            item(1, "DSCF4410.RAF", BatchItemStatus.Sidecar),
            item(2, "IMG_2041.HEIC", BatchItemStatus.Sidecar),
            item(3, "Screenshot_20260901.png", BatchItemStatus.Saved),
            item(4, "IMG_3001.jpg", BatchItemStatus.Copy),
            item(5, "export_final.webp", BatchItemStatus.Saved),
            item(6, "scan_004.tif", BatchItemStatus.Skipped, BatchItemReason.NothingToChange),
            item(7, "PXL_20260913_190012.jpg", BatchItemStatus.Failed, BatchItemReason.SaveFailed, SaveError.VerificationFailed),
        ),
    )
}
