package io.github.fishpimp.exiflab.metadata.edit

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.IPTC_RECORD_VERSION
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.assertIdempotent
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.edit
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.exifRaw
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.fullJpeg
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.jpeg
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.plan
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.xmpRaw
import io.github.fishpimp.exiflab.metadata.fixtures.Containers
import io.github.fishpimp.exiflab.metadata.fixtures.Photos
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.assertUntouched
import org.junit.Test

class AuthorshipPlannerTest {
    @Test fun authorshipIsWrittenToExifXmpAndIptc() {
        val edited = edit(fullJpeg(), EditOperation.SetAuthorship("John Smith", "(c) 2026 John Smith"))
        assertThat(edited.exif("exif-ifd0", 0x013B)).isEqualTo("John Smith")
        assertThat(edited.exif("exif-ifd0", 0x8298)).isEqualTo("(c) 2026 John Smith")
        assertThat(edited.xmp("dc:creator[1]")).isEqualTo("John Smith")
        assertThat(edited.xmp("dc:rights[1]")).isEqualTo("(c) 2026 John Smith")
        assertThat(edited.iptc(80)).isEqualTo("John Smith")
        assertThat(edited.iptc(116)).isEqualTo("(c) 2026 John Smith")
        assertThat(edited.plan.warnings).isEmpty()
        val artist = edited.diff("Artist")
        assertThat(artist.before).isEqualTo("Jane Doe")
        assertThat(artist.after).isEqualTo("John Smith")
        assertThat(edited.diff("dc:creator").after).isEqualTo("John Smith")
        assertThat(edited.diff("By-line").after).isEqualTo("John Smith")
        assertUntouched(
            edited.before, edited.after,
            setOf("exif-ifd0:315", "exif-ifd0:33432", "xmp:dc:creator[1]", "xmp:dc:rights[1]", "iptc:592", "iptc:628", IPTC_RECORD_VERSION),
        )
        edited.assertIdempotent(EditOperation.SetAuthorship("John Smith", "(c) 2026 John Smith"))
    }

    @Test fun typographicCharactersBecomeTheirAsciiSpellingInExif() {
        val edited = edit(fullJpeg(), EditOperation.SetAuthorship("Jane “JD” Doe", "© 2026 Jane Doe – all rights reserved"))
        assertThat(edited.exif("exif-ifd0", 0x013B)).isEqualTo("Jane \"JD\" Doe")
        assertThat(edited.exif("exif-ifd0", 0x8298)).isEqualTo("(c) 2026 Jane Doe - all rights reserved")
        assertThat(edited.xmp("dc:rights[1]")).isEqualTo("© 2026 Jane Doe – all rights reserved")
        assertThat(edited.iptc(80)).isEqualTo("Jane “JD” Doe")
        assertThat(edited.plan.warnings).isEmpty()
    }

    @Test fun lettersExifCannotHoldGoToXmpAndIptcOnly() {
        val edited = edit(fullJpeg(), EditOperation.SetAuthorship("Åsa Öberg", "© Åsa Öberg"))
        // The old ASCII names would contradict the new ones, so they are removed.
        assertThat(edited.exif("exif-ifd0", 0x013B)).isNull()
        assertThat(edited.exif("exif-ifd0", 0x8298)).isNull()
        assertThat(edited.xmp("dc:creator[1]")).isEqualTo("Åsa Öberg")
        assertThat(edited.iptc(80)).isEqualTo("Åsa Öberg")
        assertThat(edited.iptc(116)).isEqualTo("© Åsa Öberg")
        assertThat(edited.plan.warnings).containsExactly(
            "The artist has characters EXIF cannot store; it is saved in XMP and IPTC only",
            "The copyright has characters EXIF cannot store; it is saved in XMP and IPTC only",
        )
        assertThat(edited.diff("Artist").kind).isEqualTo(FieldDiff.Kind.Removed)
    }

    @Test fun nullRemovesAuthorAndCopyrightEverywhere() {
        val edited = edit(fullJpeg(), EditOperation.SetAuthorship(null, "  "))
        assertThat(edited.exif("exif-ifd0", 0x013B)).isNull()
        assertThat(edited.exif("exif-ifd0", 0x8298)).isNull()
        assertThat(edited.xmp("dc:creator[1]")).isNull()
        assertThat(edited.xmp("dc:rights[1]")).isNull()
        assertThat(edited.iptc(80)).isNull()
        assertThat(edited.iptc(116)).isNull()
        assertThat(edited.plan.diff.map { it.kind }.distinct()).containsExactly(FieldDiff.Kind.Removed)
    }

    @Test fun oneHalfCanBeChangedAlone() {
        val edited = edit(fullJpeg(), EditOperation.SetAuthorship("John Smith", null, changeCopyright = false))
        assertThat(edited.exif("exif-ifd0", 0x013B)).isEqualTo("John Smith")
        assertThat(edited.exif("exif-ifd0", 0x8298)).isEqualTo("(c) Jane Doe")
        assertThat(edited.xmp("dc:rights[1]")).isEqualTo("(c) Jane Doe")
        assertThat(edited.plan.diff.map { it.label }).containsExactly("Artist", "dc:creator", "By-line")
    }

    @Test fun jpegGainsIptcButPngDoesNot() {
        val jpeg = edit(jpeg(), EditOperation.SetAuthorship("John Smith", null, changeCopyright = false))
        assertThat(jpeg.iptc(80)).isEqualTo("John Smith")
        // No XMP existed; authorship still goes there since it is the standard home for dc:creator.
        assertThat(jpeg.xmp("dc:creator[1]")).isEqualTo("John Smith")

        val png = Containers.png()
        val plan = plan(png, EditOperation.SetAuthorship("John Smith", "(c) John Smith"))
        assertThat(plan.changes.iptc).isEmpty()
        val after = Photos.read(WriteFixtures.write(png, plan.changes).bytes)
        assertThat(after.exifRaw("exif-ifd0", 0x013B)).isEqualTo("John Smith")
        assertThat(after.xmpRaw("dc:rights[1]")).isEqualTo("(c) John Smith")
    }

    @Test fun namesLongerThanIptcAllowsStayOutOfIptc() {
        val long = "Jane Doe of the Very Long Family Name"
        val edited = edit(fullJpeg(), EditOperation.SetAuthorship(long, null, changeCopyright = false))
        assertThat(edited.exif("exif-ifd0", 0x013B)).isEqualTo(long)
        assertThat(edited.iptc(80)).isNull()
        assertThat(edited.plan.warnings).containsExactly("The artist is longer than IPTC allows (32 bytes); it is saved in EXIF and XMP only")
    }
}
