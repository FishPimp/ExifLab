package io.github.fishpimp.exiflab.metadata.edit

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.IPTC_RECORD_VERSION
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.Edited
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.assertIdempotent
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.edit
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.fullJpeg
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.jpeg
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.plan
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.xmpTags
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory
import io.github.fishpimp.exiflab.metadata.write.MetadataBlock
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.assertUntouched
import org.junit.Test

class StripPlannerTest {
    private fun strip(vararg categories: StripCategory): Edited = edit(fullJpeg(), EditOperation.Strip(categories.toSet())).also {
        it.assertIdempotent(EditOperation.Strip(categories.toSet()))
    }

    private fun Edited.ids() = after.directories.map { it.id }

    @Test fun stripAllKeepsOrientationAndColorProfile() {
        val edited = strip(StripCategory.All)
        assertThat(edited.ids()).containsNoneOf("exif-subifd", "exif-interop", "exif-thumbnail", "gps", "makernote-canon", "xmp", "iptc", "jpeg-comment")
        assertThat(edited.ids()).contains("icc")
        assertThat(edited.exif("exif-ifd0", 0x0112)).isEqualTo("6")
        assertThat(edited.after.summary.orientation).isEqualTo(6)
        assertThat(edited.after.summary.colorProfile).isEqualTo("sRGB built-in")
        assertThat(edited.after.sensitiveFindings).isEmpty()
        assertThat(edited.plan.changes.removeBlocks).containsExactly(
            MetadataBlock.Exif, MetadataBlock.Xmp, MetadataBlock.Iptc, MetadataBlock.PhotoshopResources, MetadataBlock.Comments,
        )

        val labels = edited.plan.diff.map { it.label }
        // One line per block, then the fields that matter most; orientation is unchanged.
        assertThat(labels).containsAtLeast("EXIF data", "MakerNote", "Thumbnail", "XMP packet", "IPTC data", "Comments")
        assertThat(labels).containsAtLeast("Make", "Model", "Artist", "Copyright", "Date/Time Original", "Body Serial Number", "GPS Latitude", "GPS Longitude")
        assertThat(labels).containsAtLeast("dc:creator", "mwg-rs:Regions", "aux:SerialNumber", "By-line", "City")
        assertThat(labels).containsNoneOf("Orientation", "Exposure Time", "F-Number", "GPS Version ID", "xmp:Rating", "ICC profile")
        assertThat(edited.diff("Comments").before).isEqualTo("Shot by Jane")
        assertThat(edited.diff("MakerNote").group).isEqualTo(DirectoryGroup.MakerNote)
        assertThat(edited.diff("XMP packet").before).endsWith(" properties")
        assertThat(edited.plan.diff.all { it.kind == FieldDiff.Kind.Removed }).isTrue()
        // Lines are grouped like the viewer: EXIF, GPS, MakerNote, XMP, IPTC, container.
        assertThat(edited.plan.diff.map { it.group.ordinal }).isInOrder()
    }

    @Test fun stripLocation() {
        val edited = strip(StripCategory.Location)
        assertThat(edited.ids()).doesNotContain("gps")
        assertThat(edited.after.xmpTags().map { it.name }.filter { it.startsWith("exif:GPS") || it.startsWith("photoshop:C") }).isEmpty()
        assertThat(edited.iptc(90)).isNull()
        assertThat(edited.iptc(101)).isNull()
        assertUntouched(
            edited.before, edited.after,
            setOf(
                "gps:", "xmp:exif:GPSLatitude", "xmp:exif:GPSLongitude", "xmp:exif:GPSTimeStamp", "xmp:photoshop:City", "xmp:photoshop:Country",
                "iptc:602", "iptc:613", IPTC_RECORD_VERSION,
            ),
        )
    }

    @Test fun stripDeviceIdentifiersKeepsMakerNotes() {
        val edited = strip(StripCategory.DeviceIdentifiers)
        for (tag in listOf(0xA431, 0xA435, 0xA420)) assertThat(edited.exif("exif-subifd", tag)).isNull()
        assertThat(edited.xmp("xmpMM:DocumentID")).isNull()
        assertThat(edited.xmp("xmpMM:InstanceID")).isNull()
        assertThat(edited.xmp("aux:SerialNumber")).isNull()
        assertThat(edited.ids()).contains("makernote-canon")
        assertThat(edited.xmp("xmpMM:History[1]/stEvt:action")).isEqualTo("saved")
        assertUntouched(
            edited.before, edited.after,
            setOf("exif-subifd:42033", "exif-subifd:42037", "exif-subifd:42016", "xmp:xmpMM:DocumentID", "xmp:xmpMM:InstanceID", "xmp:aux:SerialNumber"),
        )
    }

    @Test fun stripDeviceInfo() {
        val edited = strip(StripCategory.DeviceInfo)
        for (tag in listOf(0x010F, 0x0110, 0x0131, 0x013C)) assertThat(edited.exif("exif-ifd0", tag)).isNull()
        for (tag in listOf(0xA433, 0xA434)) assertThat(edited.exif("exif-subifd", tag)).isNull()
        assertThat(edited.xmp("tiff:Make")).isNull()
        assertThat(edited.xmp("xmp:CreatorTool")).isNull()
        assertThat(edited.xmp("aux:Lens")).isNull()
        assertUntouched(
            edited.before, edited.after,
            setOf(
                "exif-ifd0:271", "exif-ifd0:272", "exif-ifd0:305", "exif-ifd0:316", "exif-subifd:42035", "exif-subifd:42036",
                "xmp:tiff:Make", "xmp:xmp:CreatorTool", "xmp:aux:Lens",
                // Without Make the reader no longer decodes the Canon MakerNote; its bytes stay.
                "makernote-canon:", "exif-subifd:37500",
            ),
        )
        assertThat(edited.exif("exif-subifd", 0x927C)).isNotNull()
    }

    @Test fun stripPersonNames() {
        val edited = strip(StripCategory.PersonNames)
        assertThat(edited.exif("exif-ifd0", 0x013B)).isNull()
        assertThat(edited.exif("exif-ifd0", 0x8298)).isNull()
        assertThat(edited.exif("exif-subifd", 0xA430)).isNull()
        assertThat(edited.xmp("dc:creator[1]")).isNull()
        assertThat(edited.xmp("dc:rights[1]")).isNull()
        assertThat(edited.xmp("xmpRights:Marked")).isNull()
        for (dataset in listOf(80, 110, 116)) assertThat(edited.iptc(dataset)).isNull()
        assertThat(edited.xmp("dc:title[1]")).isEqualTo("Old title")
        assertUntouched(
            edited.before, edited.after,
            setOf(
                "exif-ifd0:315", "exif-ifd0:33432", "exif-subifd:42032", "xmp:dc:creator[1]", "xmp:dc:rights[1]", "xmp:xmpRights:Marked",
                "iptc:592", "iptc:622", "iptc:628", IPTC_RECORD_VERSION,
            ),
        )
    }

    @Test fun stripPeople() {
        val edited = strip(StripCategory.People)
        assertThat(edited.after.xmpTags().map { it.name }.filter { it.startsWith("mwg-rs:") || it.startsWith("Iptc4xmpExt:") }).isEmpty()
        assertThat(edited.xmp("dc:creator[1]")).isEqualTo("Jane Doe")
        assertThat(edited.plan.diff.map { it.label }).containsExactly("mwg-rs:Regions", "Iptc4xmpExt:PersonInImage")
        assertUntouched(edited.before, edited.after, setOf("xmp:mwg-rs:", "xmp:Iptc4xmpExt:"))
    }

    @Test fun stripDates() {
        val edited = strip(StripCategory.Dates)
        assertThat(edited.after.summary.capturedAt).isNull()
        assertThat(edited.exif("exif-ifd0", 0x0132)).isNull()
        for (tag in listOf(0x9003, 0x9004, 0x9010, 0x9011, 0x9012, 0x9291, 0x9292)) assertThat(edited.exif("exif-subifd", tag)).isNull()
        assertThat(edited.exif("gps", 0x0007)).isNull()
        assertThat(edited.exif("gps", 0x001D)).isNull()
        assertThat(edited.exif("gps", 0x0002)).isEqualTo("59/1 19/1 45/1")
        for (path in listOf("xmp:CreateDate", "xmp:ModifyDate", "exif:DateTimeOriginal", "photoshop:DateCreated", "exif:GPSTimeStamp")) {
            assertThat(edited.xmp(path)).isNull()
        }
        assertThat(edited.iptc(55)).isNull()
        assertThat(edited.iptc(60)).isNull()
        assertUntouched(
            edited.before, edited.after,
            setOf(
                "exif-ifd0:306", "exif-subifd:36867", "exif-subifd:36868", "exif-subifd:36880", "exif-subifd:36881", "exif-subifd:36882",
                "exif-subifd:37521", "exif-subifd:37522", "gps:7", "gps:29", "xmp:xmp:CreateDate", "xmp:xmp:ModifyDate",
                "xmp:exif:DateTimeOriginal", "xmp:photoshop:DateCreated", "xmp:exif:GPSTimeStamp", "iptc:567", "iptc:572", IPTC_RECORD_VERSION,
            ),
        )
    }

    @Test fun stripMakerNotes() {
        val edited = strip(StripCategory.MakerNotes)
        assertThat(edited.ids()).doesNotContain("makernote-canon")
        assertThat(edited.plan.changes.removeBlocks).containsExactly(MetadataBlock.MakerNote)
        assertThat(edited.plan.diff.map { it.label }).containsExactly("MakerNote")
        assertThat(edited.diff("MakerNote").before).isEqualTo("Canon Makernote, 2 tags")
        assertUntouched(edited.before, edited.after, setOf("makernote-canon:", "exif-subifd:37500"))
    }

    @Test fun stripThumbnail() {
        val edited = strip(StripCategory.Thumbnail)
        assertThat(edited.ids()).doesNotContain("exif-thumbnail")
        assertThat(edited.plan.diff.map { it.label }).containsExactly("Thumbnail")
        assertUntouched(edited.before, edited.after, setOf("exif-thumbnail:"))
    }

    @Test fun stripEditHistory() {
        val edited = strip(StripCategory.EditHistory)
        assertThat(edited.after.xmpTags().map { it.name }.filter { it.startsWith("xmpMM:") }).isEmpty()
        assertThat(edited.xmp("photoshop:History")).isNull()
        assertThat(edited.plan.diff.map { it.label }).containsExactly("xmpMM:DocumentID", "xmpMM:InstanceID", "xmpMM:History", "photoshop:History")
        assertUntouched(edited.before, edited.after, setOf("xmp:xmpMM:", "xmp:photoshop:History"))
    }

    @Test fun combinedCategoriesAndCleanFiles() {
        val edited = strip(StripCategory.Location, StripCategory.PersonNames, StripCategory.People)
        assertThat(edited.after.sensitiveFindings.map { it.category }).containsNoneOf(
            SensitivityCategory.Location,
            SensitivityCategory.People,
        )
        val clean = plan(jpeg(exif = null), EditOperation.Strip(StripCategory.entries.toSet()))
        assertThat(clean.changes.isEmpty).isTrue()
        assertThat(clean.diff).isEmpty()
    }
}
