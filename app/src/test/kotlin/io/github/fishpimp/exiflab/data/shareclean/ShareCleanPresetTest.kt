package io.github.fishpimp.exiflab.data.shareclean

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.edit.StripCategory
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.LocationStatus
import io.github.fishpimp.exiflab.metadata.model.MetadataDirectory
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.PhotoSummary
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory
import io.github.fishpimp.exiflab.screenshots.FakeReports
import io.github.fishpimp.exiflab.screenshots.FakeReports.Tag
import org.junit.Test

/** What each preset removes, and how a report's contents map to the kinds Share clean names. */
class ShareCleanPresetTest {
    @Test
    fun `presets map to their strip categories`() {
        val custom = setOf(StripCategory.Dates, StripCategory.People)
        assertThat(ShareCleanPreset.LocationOnly.categories(custom)).containsExactly(StripCategory.Location)
        assertThat(ShareCleanPreset.LocationAndDevice.categories(custom)).containsExactly(
            StripCategory.Location,
            StripCategory.DeviceIdentifiers,
            StripCategory.DeviceInfo,
            StripCategory.MakerNotes,
            StripCategory.Thumbnail,
        )
        assertThat(ShareCleanPreset.Everything.categories(custom)).containsExactly(StripCategory.All)
        assertThat(ShareCleanPreset.Custom.categories(custom)).isEqualTo(custom)
    }

    @Test
    fun `a custom choice with nothing ticked cannot be shared, the others always can`() {
        assertThat(ShareCleanChoice(ShareCleanPreset.Custom, custom = emptySet()).canShare).isFalse()
        assertThat(ShareCleanChoice(ShareCleanPreset.Everything, custom = emptySet()).canShare).isTrue()
        assertThat(ShareCleanChoice().preset).isEqualTo(ShareCleanPreset.LocationAndDevice)
        assertThat(ShareCleanChoice().includeRawPreviews).isTrue()
    }

    private val camera = report(
        locationStatus = LocationStatus.Present,
        FakeReports.directory(
            "exif-ifd0", "Exif IFD0", DirectoryGroup.Exif,
            listOf(
                Tag(0x010F, "Make", "FUJIFILM"),
                Tag(0x0110, "Model", "X-T5"),
                Tag(0x0112, "Orientation", "Top, left side"),
                Tag(0x013B, "Artist", "Maja", sensitivity = SensitivityCategory.PersonName),
            ),
        ),
        FakeReports.directory(
            "exif-subifd", "Exif SubIFD", DirectoryGroup.Exif,
            listOf(
                Tag(0x9003, "Date/Time Original", "2026:06:14 17:42:07"),
                Tag(0xA431, "Body Serial Number", "5CA1", sensitivity = SensitivityCategory.SerialNumber),
                Tag(0xA435, "Lens Serial Number", "LS-9", sensitivity = SensitivityCategory.SerialNumber),
                Tag(0x829A, "Exposure Time", "1/250 sec"),
            ),
        ),
        FakeReports.directory("gps", "GPS", DirectoryGroup.Gps, listOf(Tag(0x0002, "GPS Latitude", "59° 19' 45\"", sensitivity = SensitivityCategory.Location))),
        FakeReports.directory(
            "makernote-fujifilm", "Fujifilm Makernote", DirectoryGroup.MakerNote,
            listOf(Tag(0x0010, "Serial Number", "FF0123", sensitivity = SensitivityCategory.SerialNumber), Tag(0x1000, "Quality", "FINE")),
        ),
        FakeReports.directory("exif-thumbnail", "Exif Thumbnail", DirectoryGroup.Exif, listOf(Tag(0x0201, "Thumbnail Offset", "1234"))),
        FakeReports.directory(
            "xmp", "XMP", DirectoryGroup.Xmp,
            listOf(
                Tag(null, "xmpMM:DocumentID", "xmp.did:1", sensitivity = SensitivityCategory.DeviceIdentifier),
                Tag(null, "xmpMM:DerivedFrom/stRef:documentID", "xmp.did:0", sensitivity = SensitivityCategory.DeviceIdentifier),
                Tag(null, "xmp:CreatorTool", "Lightroom"),
            ),
        ),
        FakeReports.directory("icc", "ICC Profile", DirectoryGroup.Icc, listOf(Tag(null, "Profile Description", "Display P3"))),
    )

    private fun report(locationStatus: LocationStatus, vararg directories: MetadataDirectory) = MetadataReport(
        format = ImageFormat.Jpeg,
        fileName = "DSCF0001.JPG",
        fileSize = 1L,
        summary = PhotoSummary(),
        location = null,
        locationStatus = locationStatus,
        directories = directories.toList(),
        sensitiveFindings = FakeReports.findings(directories.toList()),
        warnings = emptyList(),
    )

    @Test
    fun `location only covers the location`() {
        val inventory = MetadataInventory.of(camera, ShareCleanPreset.LOCATION_ONLY)
        assertThat(inventory.kinds).containsExactly(MetadataKind.Location)
        assertThat(inventory.count(MetadataKind.Location)).isEqualTo(1)
    }

    @Test
    fun `location and device counts serials outside maker notes and leaves the edit history ids`() {
        val inventory = MetadataInventory.of(camera, ShareCleanPreset.LOCATION_AND_DEVICE)
        assertThat(inventory.kinds).containsExactly(
            MetadataKind.Location,
            MetadataKind.SerialNumbers,
            MetadataKind.UniqueIds,
            MetadataKind.CameraDetails,
            MetadataKind.MakerNotes,
            MetadataKind.Thumbnail,
        ).inOrder()
        assertThat(inventory.count(MetadataKind.SerialNumbers)).isEqualTo(2)
        // Only the top-level document id; DerivedFrom belongs to the edit history.
        assertThat(inventory.count(MetadataKind.UniqueIds)).isEqualTo(1)
        assertThat(inventory.count(MetadataKind.CameraDetails)).isEqualTo(3)
        assertThat(inventory.count(MetadataKind.MakerNotes)).isEqualTo(2)
    }

    @Test
    fun `everything also counts the remaining fields but never orientation or the color profile`() {
        val inventory = MetadataInventory.of(camera, ShareCleanPreset.EVERYTHING)
        assertThat(inventory.kinds).containsAtLeast(MetadataKind.Names, MetadataKind.Dates, MetadataKind.EditHistory, MetadataKind.OtherMetadata)
        // Exposure time is the one field no other kind claims.
        assertThat(inventory.count(MetadataKind.OtherMetadata)).isEqualTo(1)

        val stripped = report(
            LocationStatus.Absent,
            FakeReports.directory("exif-ifd0", "Exif IFD0", DirectoryGroup.Exif, listOf(Tag(0x0112, "Orientation", "Rotate 90 CW"))),
            FakeReports.directory("icc", "ICC Profile", DirectoryGroup.Icc, listOf(Tag(null, "Profile Description", "Display P3"))),
        )
        assertThat(MetadataInventory.of(stripped, ShareCleanPreset.EVERYTHING).isEmpty).isTrue()
    }

    @Test
    fun `without the private categories nothing is covered`() {
        val kept = MetadataInventory.of(camera, setOf(StripCategory.Location, StripCategory.PersonNames))
        val removed = MetadataInventory.of(camera, ShareCleanPreset.LOCATION_ONLY)
        assertThat(kept.without(removed)).containsExactly(MetadataKind.Names)
        assertThat(MetadataInventory.of(camera, emptySet()).isEmpty).isTrue()
    }
}
