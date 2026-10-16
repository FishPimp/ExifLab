package io.github.fishpimp.exiflab.metadata.edit

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.catalog
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.edit
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.fullJpeg
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.jpeg
import io.github.fishpimp.exiflab.metadata.fixtures.Containers
import io.github.fishpimp.exiflab.metadata.fixtures.Photos
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.Rational
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory
import io.github.fishpimp.exiflab.metadata.write.ExifIfd
import io.github.fishpimp.exiflab.metadata.write.ExifValue
import io.github.fishpimp.exiflab.metadata.write.IptcChange
import io.github.fishpimp.exiflab.metadata.write.MetadataChanges
import io.github.fishpimp.exiflab.metadata.write.XmpChange
import org.junit.Assert.assertThrows
import org.junit.Test

class TagCatalogTest {
    private fun field(id: String): EditableField = catalog.field(id) ?: error("No field $id")

    private fun valid(id: String, input: String): String? = (catalog.validate(field(id), input) as? Validation.Valid)?.normalized

    private fun error(id: String, input: String): ValidationError? = (catalog.validate(field(id), input) as? Validation.Invalid)?.error

    @Test fun fieldsAreUniqueAndNamedLikeTheViewer() {
        val ids = catalog.fields.map { it.id }
        assertThat(ids).containsNoDuplicates()
        assertThat(ids.all { catalog.field(it)?.id == it }).isTrue()
        assertThat(catalog.field("exif:ifd0:0xFFFF")).isNull()
        assertThat(field("exif:subifd:0x9003").label).isEqualTo("Date/Time Original")
        assertThat(field("exif:subifd:0x829A").label).isEqualTo("Exposure Time")
        assertThat(field("exif:ifd0:0x013B").label).isEqualTo("Artist")
        assertThat(field("iptc:2:25").label).isEqualTo("Keywords")
        assertThat(field("iptc:2:120").label).isEqualTo("Caption/Abstract")
        assertThat(field("xmp:dc:title").label).isEqualTo("dc:title")
        assertThat(field("iptc:2:80").sensitivity).isEqualTo(SensitivityCategory.PersonName)
        assertThat(field("exif:subifd:0xA431").sensitivity).isEqualTo(SensitivityCategory.SerialNumber)
        assertThat(field("xmp:photoshop:City").group).isEqualTo(DirectoryGroup.Xmp)
        assertThat(catalog.fields.filter { it.common }.map { it.id }).containsExactly(
            FieldIds.TITLE, FieldIds.DESCRIPTION, FieldIds.KEYWORDS, FieldIds.RATING, FieldIds.ARTIST, FieldIds.COPYRIGHT,
            FieldIds.ORIENTATION, FieldIds.DATE_TAKEN,
        )
        val orientation = field(FieldIds.ORIENTATION).kind as FieldKind.Choice
        assertThat(orientation.options.map { it.value }).containsExactly("1", "2", "3", "4", "5", "6", "7", "8").inOrder()
        assertThat(orientation.options[5].label).isEqualTo("Rotate 90 CW")
    }

    @Test fun currentValuesComeFromTheReport() {
        val report = Photos.read(fullJpeg())
        fun current(id: String) = catalog.currentValue(field(id), report)
        assertThat(current(FieldIds.ARTIST)).isEqualTo("Jane Doe")
        assertThat(current(FieldIds.DATE_TAKEN)).isEqualTo("2024-05-01 14:03:22")
        assertThat(current(FieldIds.ORIENTATION)).isEqualTo("6")
        assertThat(current("exif:subifd:0x829A")).isEqualTo("1/250")
        assertThat(current("exif:subifd:0x829D")).isEqualTo("1.8")
        assertThat(current("exif:subifd:0x920A")).isEqualTo("6.9")
        assertThat(current("exif:subifd:0x9204")).isEqualTo("-0.7")
        assertThat(current("exif:subifd:0x9209")).isEqualTo("25")
        assertThat(current("exif:subifd:0x8827")).isEqualTo("400")
        assertThat(current("exif:subifd:0x9011")).isEqualTo("+02:00")
        assertThat(current(FieldIds.TITLE)).isEqualTo("Old title")
        assertThat(current(FieldIds.KEYWORDS)).isEqualTo("alpha, beta")
        assertThat(current(FieldIds.RATING)).isEqualTo("3")
        assertThat(current("iptc:2:25")).isEqualTo("alpha, beta")
        assertThat(current("iptc:2:116")).isEqualTo("(c) Jane Doe")
        assertThat(current("exif:subifd:0x9286")).isNull()
        assertThat(current("xmp:photoshop:Headline")).isNull()
    }

    @Test fun textValidation() {
        assertThat(valid(FieldIds.ARTIST, "  Jane Doe ")).isEqualTo("Jane Doe")
        assertThat(error(FieldIds.ARTIST, "   ")).isEqualTo(ValidationError.Empty)
        // Artist and copyright accept any text: it goes to XMP and IPTC when EXIF cannot hold it.
        assertThat(valid(FieldIds.ARTIST, "Åsa Öberg")).isEqualTo("Åsa Öberg")
        assertThat(error("exif:ifd0:0x010F", "Nikon®")).isEqualTo(ValidationError.NotAscii)
        assertThat(valid("exif:ifd0:0x010F", "Line\nbreak")).isEqualTo("Line break")
        assertThat(valid(FieldIds.DESCRIPTION, "First line\r\nsecond line\n")).isEqualTo("First line\nsecond line")
        assertThat(error("exif:subifd:0xA420", "0".repeat(33))).isEqualTo(ValidationError.TooLong)
        // IPTC limits count bytes: 16 two-byte letters fit By-line's 32, 17 do not.
        assertThat(valid("iptc:2:80", "Å".repeat(16))).isNotNull()
        assertThat(error("iptc:2:80", "Å".repeat(17))).isEqualTo(ValidationError.TooLong)
        assertThat(error("iptc:2:116", "x".repeat(129))).isEqualTo(ValidationError.TooLong)
        assertThat(valid("iptc:2:120", "x".repeat(2000))).isNotNull()
    }

    @Test fun numberValidationNormalizes() {
        val exposure = "exif:subifd:0x829A"
        assertThat(valid(exposure, "0.004")).isEqualTo("1/250")
        assertThat(valid(exposure, "1/250 s")).isEqualTo("1/250")
        assertThat(valid(exposure, "2/500")).isEqualTo("1/250")
        assertThat(valid(exposure, "0.3")).isEqualTo("0.3")
        assertThat(valid(exposure, "1/3")).isEqualTo("1/3")
        assertThat(valid(exposure, "2.5")).isEqualTo("2.5")
        assertThat(valid(exposure, "30")).isEqualTo("30")
        assertThat(error(exposure, "0")).isEqualTo(ValidationError.OutOfRange)
        assertThat(error(exposure, "-1/250")).isEqualTo(ValidationError.OutOfRange)
        assertThat(error(exposure, "fast")).isEqualTo(ValidationError.NotANumber)
        assertThat(error(exposure, "1/0")).isEqualTo(ValidationError.NotANumber)
        val fNumber = "exif:subifd:0x829D"
        assertThat(valid(fNumber, "f/2.8")).isEqualTo("2.8")
        assertThat(valid(fNumber, "F2.8")).isEqualTo("2.8")
        assertThat(valid(fNumber, "2.80")).isEqualTo("2.8")
        assertThat(valid(fNumber, "28/10")).isEqualTo("2.8")
        assertThat(error(fNumber, "0.2")).isEqualTo(ValidationError.OutOfRange)
        val bias = "exif:subifd:0x9204"
        assertThat(valid(bias, "+0.7")).isEqualTo("0.7")
        assertThat(valid(bias, "-1/3")).isEqualTo("-1/3")
        assertThat(valid(bias, "-0,7 EV")).isEqualTo("-0.7")
        assertThat(valid(bias, "−2/3")).isEqualTo("-2/3")
        assertThat(valid("exif:subifd:0x920A", "6.90 mm")).isEqualTo("6.9")
    }

    @Test fun integerDateChoiceAndListValidation() {
        val iso = "exif:subifd:0x8827"
        assertThat(valid(iso, " 400 ")).isEqualTo("400")
        assertThat(error(iso, "4OO")).isEqualTo(ValidationError.NotANumber)
        assertThat(error(iso, "0")).isEqualTo(ValidationError.OutOfRange)
        assertThat(error(iso, "70000")).isEqualTo(ValidationError.OutOfRange)
        assertThat(valid(FieldIds.RATING, "+5")).isEqualTo("5")
        assertThat(error(FieldIds.RATING, "6")).isEqualTo(ValidationError.OutOfRange)

        assertThat(valid(FieldIds.DATE_TAKEN, "2026-06-14 17:42:07")).isEqualTo("2026-06-14 17:42:07")
        assertThat(valid(FieldIds.DATE_TAKEN, "2026:06:14 17:42")).isEqualTo("2026-06-14 17:42:00")
        assertThat(valid(FieldIds.DATE_TAKEN, "2026-06-14T17:42:07")).isEqualTo("2026-06-14 17:42:07")
        assertThat(error(FieldIds.DATE_TAKEN, "2026-02-30 10:00:00")).isEqualTo(ValidationError.InvalidDate)
        assertThat(error(FieldIds.DATE_TAKEN, "yesterday")).isEqualTo(ValidationError.InvalidDate)
        assertThat(error(FieldIds.DATE_TAKEN, "")).isEqualTo(ValidationError.Empty)

        assertThat(valid(FieldIds.ORIENTATION, "6")).isEqualTo("6")
        assertThat(valid(FieldIds.ORIENTATION, "rotate 90 cw")).isEqualTo("6")
        assertThat(error(FieldIds.ORIENTATION, "9")).isEqualTo(ValidationError.InvalidChoice)
        assertThat(valid("exif:subifd:0x9209", "Auto, fired")).isEqualTo("25")
        val offset = "exif:subifd:0x9011"
        assertThat(valid(offset, "+2")).isEqualTo("+02:00")
        assertThat(valid(offset, "UTC+05:30")).isEqualTo("+05:30")
        assertThat(valid(offset, "-0930")).isEqualTo("-09:30")
        assertThat(error(offset, "+01:15")).isEqualTo(ValidationError.InvalidChoice)

        assertThat(valid(FieldIds.KEYWORDS, "beach, sunset; summer\nsunset")).isEqualTo("beach, sunset, summer")
        assertThat(error(FieldIds.KEYWORDS, " , ;")).isEqualTo(ValidationError.Empty)
        assertThat(error("iptc:2:25", "short, ${"k".repeat(65)}")).isEqualTo(ValidationError.TooLong)
        assertThat(FieldKind.TextList.split(FieldKind.TextList.join(listOf("a b", "c")))).containsExactly("a b", "c").inOrder()
    }

    @Test fun operationsForEachKindOfField() {
        fun op(id: String, input: String?) = catalog.operationFor(field(id), input)
        assertThat(op("exif:ifd0:0x010F", "Canon")).isEqualTo(EditOperation.SetExifTag(ExifIfd.Primary, 0x010F, ExifValue.Ascii("Canon")))
        assertThat(op("exif:ifd0:0x010F", null)).isEqualTo(EditOperation.RemoveExifTag(ExifIfd.Primary, 0x010F))
        assertThat(op("exif:subifd:0x829A", "0.004"))
            .isEqualTo(EditOperation.SetExifTag(ExifIfd.Exif, 0x829A, ExifValue.Rationals(listOf(Rational(1, 250)))))
        assertThat(op("exif:subifd:0x829D", "f/2.8"))
            .isEqualTo(EditOperation.SetExifTag(ExifIfd.Exif, 0x829D, ExifValue.Rationals(listOf(Rational(28, 10)))))
        assertThat(op("exif:subifd:0x9204", "-1/3"))
            .isEqualTo(EditOperation.SetExifTag(ExifIfd.Exif, 0x9204, ExifValue.SignedRationals(listOf(Rational(-1, 3)))))
        assertThat(op(FieldIds.DATE_TAKEN, "2026-06-14 17:42:07"))
            .isEqualTo(EditOperation.SetExifTag(ExifIfd.Exif, 0x9003, ExifValue.Ascii("2026:06:14 17:42:07")))
        assertThat(op(FieldIds.ORIENTATION, "Rotate 180")).isEqualTo(EditOperation.SetExifTag(ExifIfd.Primary, 0x0112, ExifValue.Shorts(listOf(3))))
        assertThat(op(FieldIds.ARTIST, "Jane")).isEqualTo(EditOperation.SetAuthorship("Jane", null, changeArtist = true, changeCopyright = false))
        assertThat(op("xmp:dc:rights", null)).isEqualTo(EditOperation.SetAuthorship(null, null, changeArtist = false, changeCopyright = true))
        assertThat(op("iptc:2:80", "Jane")).isEqualTo(EditOperation.SetAuthorship("Jane", null, changeArtist = true, changeCopyright = false))

        val dc = "http://purl.org/dc/elements/1.1/"
        val title = op(FieldIds.TITLE, "Harbour") as EditOperation.Raw
        assertThat(title.changes).isEqualTo(MetadataChanges(xmp = listOf(XmpChange.SetLocalizedText(dc, "dc", "title", "Harbour"))))
        assertThat(title.syncExisting).isEqualTo(MetadataChanges(iptc = listOf(IptcChange.Set(2, 5, listOf("Harbour")))))
        // A title too long for IPTC removes the stale IPTC copy instead.
        val long = op(FieldIds.TITLE, "t".repeat(70)) as EditOperation.Raw
        assertThat(long.syncExisting).isEqualTo(MetadataChanges(iptc = listOf(IptcChange.Remove(2, 5))))
        val keywords = op("iptc:2:25", "a, b") as EditOperation.Raw
        assertThat(keywords.changes).isEqualTo(MetadataChanges(iptc = listOf(IptcChange.Set(2, 25, listOf("a", "b")))))
        assertThat(keywords.syncExisting.xmp.single()).isEqualTo(XmpChange.SetArray(dc, "dc", "subject", listOf("a", "b"), io.github.fishpimp.exiflab.metadata.write.XmpArrayKind.Bag))
        val comment = op("exif:subifd:0x9286", "Smörgås") as EditOperation.SetExifTag
        assertThat((comment.value as ExifValue.Undefined).bytes.copyOf(8).toString(Charsets.ISO_8859_1)).isEqualTo("UNICODE\u0000")

        assertThrows(IllegalArgumentException::class.java) { op(FieldIds.RATING, "11") }
        assertThrows(IllegalArgumentException::class.java) {
            catalog.operationFor(EditableField("exif:ifd0:0x9999", "Nope", DirectoryGroup.Exif, FieldKind.TextList), "x")
        }
    }

    /** Every field, edited through the real writer on a synthetic JPEG, reads back as the normalized value. */
    @Test fun everyFieldRoundTripsThroughTheWriter() {
        val inputs = mapOf(
            "exif:ifd0:0x010F" to "Nikon", "exif:ifd0:0x0110" to "Z 8", "exif:ifd0:0x0131" to "ExifLab 1.0",
            FieldIds.ARTIST to "John Smith", FieldIds.COPYRIGHT to "(c) 2026 John Smith", "exif:ifd0:0x010E" to "A harbour at dawn",
            FieldIds.ORIENTATION to "8", "exif:ifd0:0x0132" to "2026-06-15 08:00:00", FieldIds.DATE_TAKEN to "2026-06-14 17:42:07",
            "exif:subifd:0x9004" to "2026-06-14 17:42:08", "exif:subifd:0x9010" to "+01:00", "exif:subifd:0x9011" to "-03:30",
            "exif:subifd:0x9012" to "+05:45", "exif:subifd:0x829A" to "0.002", "exif:subifd:0x829D" to "f/4", "exif:subifd:0x8827" to "3200",
            "exif:subifd:0x8822" to "3", "exif:subifd:0x9204" to "-1/3", "exif:subifd:0x9207" to "5", "exif:subifd:0x9209" to "16",
            "exif:subifd:0x920A" to "50", "exif:subifd:0xA405" to "75", "exif:subifd:0xA403" to "1", "exif:subifd:0xA433" to "Sigma",
            "exif:subifd:0xA434" to "35mm F1.4 DG", "exif:subifd:0xA431" to "SN-1", "exif:subifd:0xA435" to "LS-2",
            "exif:subifd:0xA430" to "John Smith", "exif:subifd:0x9286" to "Smörgåsbord i Göteborg",
            "exif:subifd:0xA420" to "0123456789ABCDEF0123456789ABCDEF",
            FieldIds.TITLE to "Harbour", FieldIds.DESCRIPTION to "Boats at dawn\nin the harbour", "xmp:dc:creator" to "Åsa Öberg",
            "xmp:dc:rights" to "© Åsa", FieldIds.KEYWORDS to "boats, harbour, dawn", FieldIds.RATING to "5",
            "xmp:photoshop:Headline" to "Morning", "xmp:photoshop:City" to "Göteborg", "xmp:photoshop:State" to "Västra Götaland",
            "xmp:photoshop:Country" to "Norway", "iptc:2:5" to "Harbour", "iptc:2:120" to "Boats", "iptc:2:25" to "boats, sea",
            "iptc:2:80" to "Jane Roe", "iptc:2:116" to "(c) Jane Roe", "iptc:2:90" to "Malmö", "iptc:2:101" to "Sverige",
        )
        assertThat(inputs.keys).containsExactlyElementsIn(catalog.fields.map { it.id })
        val source = fullJpeg()
        for ((id, input) in inputs) {
            val field = field(id)
            val normalized = valid(id, input)
            assertWithMessage(id).that(normalized).isNotNull()
            val edited = edit(source, catalog.operationFor(field, input))
            assertWithMessage("$id warnings").that(edited.plan.warnings.filterNot { it is PlanWarning.NonAsciiField }).isEmpty()
            assertWithMessage(id).that(catalog.currentValue(field, edited.after)).isEqualTo(normalized)
            assertWithMessage("$id diff").that(edited.plan.diff).isNotEmpty()

            val removed = edit(edited.bytes, catalog.operationFor(field, null))
            assertWithMessage("$id removed").that(catalog.currentValue(field, removed.after)).isNull()
        }
    }

    @Test fun editsKeepExistingCopiesInSync() {
        val edited = edit(fullJpeg(), catalog.operationFor(field(FieldIds.DESCRIPTION), "New description"))
        assertThat(edited.xmp("dc:description[1]")).isEqualTo("New description")
        assertThat(edited.iptc(120)).isEqualTo("New description")
        // The file had no EXIF ImageDescription; none is created.
        assertThat(edited.exif("exif-ifd0", 0x010E)).isNull()

        val keywords = edit(fullJpeg(), catalog.operationFor(field("iptc:2:25"), "one, two words"))
        assertThat(catalog.currentValue(field(FieldIds.KEYWORDS), keywords.after)).isEqualTo("one, two words")

        val title = edit(jpeg(xmp = mapOf("xmp:Rating" to "1")), catalog.operationFor(field(FieldIds.TITLE), "Harbour"))
        assertThat(title.xmp("dc:title[1]")).isEqualTo("Harbour")
        assertThat(title.after.directories.map { it.id }).doesNotContain("iptc")

        val removed = edit(fullJpeg(), catalog.operationFor(field("iptc:2:5"), null))
        assertThat(removed.iptc(5)).isNull()
        assertThat(removed.xmp("dc:title[1]")).isNull()
    }

    @Test fun catalogEditsOnPngAndWebp() {
        val png = Containers.png()
        val pngEdit = edit(png, catalog.operationFor(field(FieldIds.TITLE), "Harbour"), catalog.operationFor(field(FieldIds.DATE_TAKEN), "2026-06-14 17:42:07"))
        assertThat(catalog.currentValue(field(FieldIds.TITLE), pngEdit.after)).isEqualTo("Harbour")
        assertThat(catalog.currentValue(field(FieldIds.DATE_TAKEN), pngEdit.after)).isEqualTo("2026-06-14 17:42:07")

        val webp = Containers.webp(8, 8)
        val webpEdit = edit(webp, catalog.operationFor(field(FieldIds.ARTIST), "Jane"), catalog.operationFor(field(FieldIds.RATING), "4"))
        assertThat(catalog.currentValue(field(FieldIds.ARTIST), webpEdit.after)).isEqualTo("Jane")
        assertThat(catalog.currentValue(field("xmp:dc:creator"), webpEdit.after)).isEqualTo("Jane")
        assertThat(catalog.currentValue(field(FieldIds.RATING), webpEdit.after)).isEqualTo("4")
        assertThat(webpEdit.plan.changes.iptc).isEmpty()
    }
}
