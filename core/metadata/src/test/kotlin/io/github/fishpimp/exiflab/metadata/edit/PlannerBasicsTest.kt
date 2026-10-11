package io.github.fishpimp.exiflab.metadata.edit

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.edit
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.fullJpeg
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.jpeg
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.plan
import io.github.fishpimp.exiflab.metadata.fixtures.Photos
import io.github.fishpimp.exiflab.metadata.fixtures.Photos.tag
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.Rational
import io.github.fishpimp.exiflab.metadata.write.ExifChange
import io.github.fishpimp.exiflab.metadata.write.ExifIfd
import io.github.fishpimp.exiflab.metadata.write.ExifValue
import io.github.fishpimp.exiflab.metadata.write.IptcChange
import io.github.fishpimp.exiflab.metadata.write.MetadataBlock
import io.github.fishpimp.exiflab.metadata.write.MetadataChanges
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.assertUntouched
import io.github.fishpimp.exiflab.metadata.write.XmpChange
import org.junit.Test

class PlannerBasicsTest {
    private val xmpNs = "http://ns.adobe.com/xap/1.0/"

    @Test fun setExifTagPassesThroughWithAViewerStyleDiff() {
        val operation = EditOperation.SetExifTag(ExifIfd.Exif, 0x829A, ExifValue.Rationals(listOf(Rational(1, 60))))
        val plan = plan(fullJpeg(), operation)
        assertThat(plan.changes).isEqualTo(MetadataChanges(exif = listOf(ExifChange.Set(ExifIfd.Exif, 0x829A, operation.value))))
        assertThat(plan.diff).containsExactly(FieldDiff("Exposure Time", DirectoryGroup.Exif, "1/250 sec", "1/60 sec"))
    }

    @Test fun diffAfterValuesMatchWhatTheViewerShowsOnceWritten() {
        val edited = edit(
            fullJpeg(),
            EditOperation.SetExifTag(ExifIfd.Exif, 0x829D, ExifValue.Rationals(listOf(Rational(28, 10)))),
            EditOperation.SetExifTag(ExifIfd.Primary, 0x0112, ExifValue.Shorts(listOf(3))),
            EditOperation.SetExifTag(ExifIfd.Exif, 0x9209, ExifValue.Shorts(listOf(16))),
            EditOperation.SetExifTag(ExifIfd.Exif, 0x9204, ExifValue.SignedRationals(listOf(Rational(-1, 3)))),
        )
        for ((directory, name) in listOf("exif-subifd" to "F-Number", "exif-ifd0" to "Orientation", "exif-subifd" to "Flash", "exif-subifd" to "Exposure Bias Value")) {
            assertThat(edited.diff(name).after).isEqualTo(edited.after.tag(directory, name).displayValue)
            assertThat(edited.diff(name).before).isEqualTo(edited.before.tag(directory, name).displayValue)
        }
        assertThat(edited.diff("F-Number").after).isEqualTo("f/2.8")
    }

    @Test fun identicalValuesAreNoOps() {
        val source = fullJpeg()
        val noOps = listOf(
            EditOperation.SetExifTag(ExifIfd.Primary, 0x0110, ExifValue.Ascii("Pixel 8")),
            EditOperation.SetExifTag(ExifIfd.Exif, 0x829A, ExifValue.Rationals(listOf(Rational(1, 250)))),
            // The same value as another fraction.
            EditOperation.SetExifTag(ExifIfd.Exif, 0x829A, ExifValue.Rationals(listOf(Rational(2, 500)))),
            EditOperation.SetExifTag(ExifIfd.Exif, 0x9000, ExifValue.Undefined("0232".toByteArray())),
            EditOperation.RemoveExifTag(ExifIfd.Exif, 0xA300),
            EditOperation.Raw(MetadataChanges(xmp = listOf(XmpChange.SetProperty(xmpNs, "xmp", "Rating", "3"))), "Rating"),
            EditOperation.Raw(MetadataChanges(iptc = listOf(IptcChange.Set(2, 25, listOf("alpha", "beta")))), "Keywords"),
            EditOperation.Raw(MetadataChanges(), "Nothing"),
            EditOperation.SetAuthorship("Jane Doe", "(c) Jane Doe"),
            // The same position, also in XMP ("59,19.75N"), written with other rationals.
            EditOperation.SetLocation(59 + 19 / 60.0 + 45 / 3600.0, 18 + 4 / 60.0 + 7 / 3600.0),
        )
        for (operation in noOps) {
            val plan = plan(source, operation)
            assertWithMessage("$operation").that(plan.changes.isEmpty).isTrue()
            assertWithMessage("$operation").that(plan.diff).isEmpty()
        }
        // A change undone later in the same plan cancels out.
        val undone = plan(
            source,
            EditOperation.SetExifTag(ExifIfd.Primary, 0x0110, ExifValue.Ascii("Other")),
            EditOperation.SetExifTag(ExifIfd.Primary, 0x0110, ExifValue.Ascii("Pixel 8")),
        )
        assertThat(undone.changes.isEmpty).isTrue()
        assertThat(undone.diff).isEmpty()
    }

    @Test fun removeExifTagShowsTheRemovedValue() {
        val edited = edit(fullJpeg(), EditOperation.RemoveExifTag(ExifIfd.Exif, 0x8827))
        assertThat(edited.plan.diff).containsExactly(FieldDiff("ISO Speed Ratings", DirectoryGroup.Exif, "400", null))
        assertThat(edited.exif("exif-subifd", 0x8827)).isNull()
        assertUntouched(edited.before, edited.after, setOf("exif-subifd:34855"))
    }

    @Test fun rawChangesPassThroughAndSyncOnlyExistingCopies() {
        val operation = EditOperation.Raw(
            changes = MetadataChanges(xmp = listOf(XmpChange.SetProperty(xmpNs, "xmp", "Label", "Red"))),
            description = "Label",
            syncExisting = MetadataChanges(
                iptc = listOf(IptcChange.Set(2, 5, listOf("New title")), IptcChange.Set(2, 105, listOf("Headline")), IptcChange.Remove(2, 110)),
                exif = listOf(ExifChange.Set(ExifIfd.Primary, 0x010E, ExifValue.Ascii("Description"))),
            ),
        )
        val edited = edit(fullJpeg(), operation)
        assertThat(edited.xmp("xmp:Label")).isEqualTo("Red")
        assertThat(edited.iptc(5)).isEqualTo("New title")
        // Headline and ImageDescription did not exist, so they are not created; removals always apply.
        assertThat(edited.iptc(105)).isNull()
        assertThat(edited.iptc(110)).isNull()
        assertThat(edited.exif("exif-ifd0", 0x010E)).isNull()
        assertThat(edited.plan.diff.map { it.label }).containsExactly("xmp:Label", "Object Name", "Credit")
        assertThat(edited.diff("xmp:Label").kind).isEqualTo(FieldDiff.Kind.Added)
    }

    @Test fun blockRemovalsCollapseIntoOneLine() {
        val plan = plan(fullJpeg(), EditOperation.Raw(MetadataChanges(removeBlocks = setOf(MetadataBlock.IccProfile, MetadataBlock.Comments)), "Clean"))
        assertThat(plan.changes.removeBlocks).containsExactly(MetadataBlock.IccProfile, MetadataBlock.Comments)
        assertThat(plan.diff).containsExactly(
            FieldDiff("ICC profile", DirectoryGroup.Icc, "sRGB built-in", null),
            FieldDiff("Comments", DirectoryGroup.Container, "Shot by Jane", null),
        ).inOrder()
        // Removing blocks the file does not have changes nothing.
        val clean = plan(jpeg(exif = null), EditOperation.Raw(MetadataChanges(removeBlocks = MetadataBlock.entries.toSet()), "Clean"))
        assertThat(clean.changes.isEmpty).isTrue()
    }

    @Test fun planningIsPureAndDeterministic() {
        val report = Photos.read(fullJpeg())
        val operations = listOf(EditOperation.Strip(setOf(StripCategory.All)), EditOperation.SetAuthorship("A", "B"))
        val first = Editing.planner.plan(report, operations)
        val second = DefaultChangePlanner().plan(report, operations)
        assertThat(second).isEqualTo(first)
        // Strip everything, then add the author back: the new values land in fresh blocks.
        assertThat(first.changes.removeBlocks).contains(MetadataBlock.Exif)
        assertThat(first.changes.exif).contains(ExifChange.Set(ExifIfd.Primary, 0x013B, ExifValue.Ascii("A")))
        assertThat(first.changes.xmp.filterIsInstance<XmpChange.SetArray>().single().values).containsExactly("A")
    }

    @Test fun littleEndianFilesAreEditedToo() {
        val edited = edit(fullJpeg(bigEndian = false), EditOperation.SetLocation(-1.25, 36.75), EditOperation.SetUtcOffset(java.time.ZoneOffset.ofHours(3)))
        assertThat(edited.after.location!!.latitude).isWithin(1e-9).of(-1.25)
        assertThat(edited.exif("exif-subifd", 0x9011)).isEqualTo("+03:00")
        assertThat(edited.after.directories.map { it.id }).contains("makernote-canon")
    }
}
