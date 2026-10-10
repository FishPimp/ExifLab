package io.github.fishpimp.exiflab.screenshots

import io.github.fishpimp.exiflab.data.history.EditBatch
import io.github.fishpimp.exiflab.data.history.EditMode
import io.github.fishpimp.exiflab.data.history.EditRecord
import io.github.fishpimp.exiflab.data.history.EditState
import io.github.fishpimp.exiflab.data.history.FieldDiffCodec
import io.github.fishpimp.exiflab.data.history.SaveError
import io.github.fishpimp.exiflab.data.photos.PhotoFormats
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.settings.BackupRetention
import io.github.fishpimp.exiflab.metadata.edit.FieldDiff
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.ui.history.HistoryDetailUiState
import io.github.fishpimp.exiflab.ui.history.HistoryList
import io.github.fishpimp.exiflab.ui.history.HistoryUiState
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** A History with every kind of entry, on a fixed clock. */
internal object FakeHistory {
    val zone: ZoneOffset = ZoneOffset.UTC
    val today: LocalDate = LocalDate.of(2026, 10, 10)

    private fun at(daysAgo: Long, hour: Int, minute: Int): Long =
        LocalDateTime.of(today.minusDays(daysAgo), java.time.LocalTime.of(hour, minute)).toInstant(zone).toEpochMilli()

    val locationDiff = listOf(
        FieldDiff("GPS Latitude", DirectoryGroup.Gps, before = null, after = "68° 12′ 31.4″ N"),
        FieldDiff("GPS Longitude", DirectoryGroup.Gps, before = null, after = "13° 31′ 9.8″ E"),
    )

    val authorDiff = listOf(
        FieldDiff("Artist", DirectoryGroup.Exif, before = null, after = "Ada Lindqvist"),
        FieldDiff("Copyright", DirectoryGroup.Exif, before = "Unknown", after = "© 2026 Ada Lindqvist"),
        FieldDiff("Date Taken", DirectoryGroup.Exif, before = "2026:09:12 08:14:55", after = "2026:09:12 10:14:55"),
        FieldDiff("dc:creator", DirectoryGroup.Xmp, before = null, after = "Ada Lindqvist"),
        FieldDiff("GPS Position", DirectoryGroup.Gps, before = "59.329300, 18.068600", after = null),
        FieldDiff("Serial Number", DirectoryGroup.MakerNote, before = "4410B33081", after = null),
    )

    fun record(
        id: String,
        name: String,
        createdAt: Long,
        mode: EditMode = EditMode.InPlace,
        state: EditState = EditState.Committed,
        diff: List<FieldDiff> = authorDiff,
        targetName: String? = null,
        backup: Boolean = mode == EditMode.InPlace,
        error: SaveError? = null,
        batchId: String? = null,
        restoresRecordId: String? = null,
        originalSize: Long = 4_212_334,
    ) = EditRecord(
        id = id,
        batchId = batchId,
        photoUri = "content://fake.provider/document/$name",
        displayName = name,
        mimeType = PhotoFormats.mimeTypeFromName(name),
        photoOrigin = PhotoOrigin.Folder,
        parentDocumentUri = "content://fake.provider/tree/camera",
        mode = mode,
        targetUri = targetName?.let { "content://fake.provider/document/$it" },
        targetName = targetName,
        state = state,
        backupPath = if (backup) "$id.bak" else null,
        originalSize = originalSize,
        originalSha256 = "0".repeat(64),
        newSha256 = "1".repeat(64),
        diffJson = FieldDiffCodec.encode(diff),
        summary = diff.map { it.label }.distinct().take(4).joinToString(", "),
        createdAt = createdAt,
        completedAt = createdAt + 800,
        error = error?.name,
        restoresRecordId = restoresRecordId,
    )

    val inPlace = record("r1", "PXL_20260912_081455.jpg", at(0, 14, 3))
    val copy = record("r2", "IMG_2041.HEIC", at(0, 11, 40), mode = EditMode.Copy, targetName = "IMG_2041 (edited).heic", originalSize = 0)
    val sidecar = record("r3", "DSCF4410.RAF", at(0, 9, 12), mode = EditMode.Sidecar, targetName = "DSCF4410.xmp", originalSize = 0)
    val failed = record(
        "r4", "export_final.webp", at(0, 8, 55),
        state = EditState.Failed, error = SaveError.ImageDataChanged, backup = false,
    )
    val damaged = record(
        "r5", "IMG_0007.jpg", at(1, 21, 30),
        state = EditState.Failed, error = SaveError.RollbackFailed,
    )
    val rolledBack = record(
        "r6", "Screenshot_20260901.png", at(1, 17, 2),
        state = EditState.RolledBack, error = SaveError.VerificationFailed, backup = false,
    )
    val restored = record("r7", "IMG_2042.HEIC", at(4, 10, 0), state = EditState.Restored)
    val restore = record("r8", "IMG_2042.HEIC", at(3, 18, 45), restoresRecordId = "r7", diff = FieldDiffCodec.invert(authorDiff))

    val batch = EditBatch("b1", "Lofoten trip: set location", createdAt = at(1, 19, 20), total = 4, succeeded = 3, failed = 1)
    val batchRecords = listOf(
        record("b1-1", "DSCF4410.RAF", at(1, 19, 20), mode = EditMode.Sidecar, targetName = "DSCF4410.xmp", batchId = "b1", diff = locationDiff, originalSize = 0),
        record("b1-2", "PXL_20260913_190012.jpg", at(1, 19, 20) + 1, batchId = "b1", diff = locationDiff),
        record("b1-3", "IMG_2042.HEIC", at(1, 19, 20) + 2, batchId = "b1", diff = locationDiff),
        record(
            "b1-4", "scan_004.tif", at(1, 19, 20) + 3, batchId = "b1", diff = locationDiff,
            state = EditState.Failed, error = SaveError.UnsupportedEdit, backup = false,
        ),
    )

    val records = listOf(inPlace, copy, sidecar, failed, damaged, rolledBack, restore, restored) + batchRecords

    val list = HistoryUiState(
        isLoading = false,
        days = HistoryList.build(records, listOf(batch), zone),
        today = today,
        zone = zone,
    )

    val empty = HistoryUiState(isLoading = false, today = today, zone = zone)

    fun detail(record: EditRecord = inPlace, backupBytes: Long? = 4_212_334) = HistoryDetailUiState(
        isLoading = false,
        record = record,
        diff = FieldDiffCodec.decode(record.diffJson),
        backupBytes = backupBytes.takeIf { record.backupPath != null },
        retention = BackupRetention.Days30,
        zone = zone,
    )
}
