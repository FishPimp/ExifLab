package io.github.fishpimp.exiflab.metadata.edit

import com.adobe.internal.xmp.XMPMetaFactory
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.ByteArrayImageSource
import io.github.fishpimp.exiflab.metadata.CorruptImageException
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.Metadata
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.exifRaw
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.iptcRaw
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.xmpRaw
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.xmpTags
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.summary.MetadataDates
import io.github.fishpimp.exiflab.metadata.write.ExifChange
import io.github.fishpimp.exiflab.metadata.write.ExifIfd
import io.github.fishpimp.exiflab.metadata.write.MetadataBlock
import io.github.fishpimp.exiflab.metadata.write.MetadataWriting
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.tagValues
import io.github.fishpimp.exiflab.metadata.write.XmpChange
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in: runs every kind of planned edit against real camera files. Point EXIFLAB_SAMPLES_DIR at
 * a directory of images and run `./gradlew :core:metadata:test --tests '*EditSamplesTest*'`. For
 * each writable sample and operation the plan is written with the real writer and read back; the
 * expected values must be there, every tag the plan does not touch must be unchanged, and planning
 * the same operation again on the result must change nothing.
 */
class EditSamplesTest {
    private class Scenario(
        val name: String,
        val operations: List<EditOperation>,
        /** Whether planning the same operations on the result must be a no-op (not for relative edits). */
        val repeatable: Boolean = true,
        /** Problems with the written result, given the reports before and after. */
        val check: (before: MetadataReport, after: MetadataReport) -> List<String>,
    )

    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val stockholm = ZoneId.of("Europe/Stockholm")
    private val captureTime = LocalDateTime.of(2021, 2, 3, 4, 5, 6, 250_000_000)

    private fun exifDate(report: MetadataReport): LocalDateTime? = MetadataDates.parseExif(report.exifRaw("exif-subifd", 0x9003))?.local

    private val scenarios = listOf(
        Scenario("set capture time", listOf(EditOperation.SetCaptureTime(captureTime, ZoneOffset.ofHours(1), alsoModifyDate = true))) { _, after ->
            listOfNotNull(
                "capture time ${after.summary.capturedAt}".takeIf { after.summary.capturedAt != captureTime },
                "offset ${after.summary.utcOffset}".takeIf { after.summary.utcOffset != ZoneOffset.ofHours(1) },
                "DateTime ${after.exifRaw("exif-ifd0", 0x0132)}".takeIf { after.exifRaw("exif-ifd0", 0x0132) != "2021:02:03 04:05:06" },
            )
        },
        Scenario("shift", listOf(EditOperation.ShiftCaptureTime(Duration.ofMinutes(90))), repeatable = false) { before, after ->
            val old = exifDate(before) ?: return@Scenario emptyList()
            listOfNotNull("DateTimeOriginal ${exifDate(after)}".takeIf { exifDate(after) != old.plusMinutes(90) })
        },
        Scenario("change time zone", listOf(EditOperation.ChangeTimeZone(tokyo, stockholm)), repeatable = false) { before, after ->
            val old = exifDate(before) ?: return@Scenario emptyList()
            val expected = ZonedDateTime.ofLocal(old, tokyo, null).withZoneSameInstant(stockholm)
            listOfNotNull(
                "DateTimeOriginal ${exifDate(after)}".takeIf { exifDate(after) != expected.toLocalDateTime() },
                "offset ${after.summary.utcOffset}".takeIf { after.summary.utcOffset != expected.offset },
            )
        },
        Scenario("set offset", listOf(EditOperation.SetUtcOffset(ZoneOffset.ofHours(-5)))) { before, after ->
            if (exifDate(before) == null) return@Scenario emptyList()
            listOfNotNull(
                "DateTimeOriginal moved".takeIf { exifDate(after) != exifDate(before) },
                "OffsetTimeOriginal ${after.exifRaw("exif-subifd", 0x9011)}".takeIf { after.exifRaw("exif-subifd", 0x9011) != "-05:00" },
            )
        },
        Scenario("set location", listOf(EditOperation.SetLocation(-33.856784, 151.215297, 25.0))) { _, after ->
            val location = after.location
            listOfNotNull(
                "location $location".takeIf {
                    location == null || abs(location.latitude + 33.856784) > 1e-6 || abs(location.longitude - 151.215297) > 1e-6 ||
                        location.altitudeMeters != 25.0
                },
            )
        },
        Scenario("remove location", listOf(EditOperation.RemoveLocation(includePlaceNames = true))) { _, after ->
            listOfNotNull(
                "location ${after.location}".takeIf { after.location != null || after.directories.any { it.id == "gps" } },
                "XMP GPS left".takeIf { after.xmpTags().any { it.name.startsWith("exif:GPS") || it.name.startsWith("photoshop:City") } },
            )
        },
        Scenario("authorship", listOf(EditOperation.SetAuthorship("ExifLab Tester", "© 2026 ExifLab"))) { before, after ->
            listOfNotNull(
                "Artist ${after.exifRaw("exif-ifd0", 0x013B)}".takeIf { after.exifRaw("exif-ifd0", 0x013B) != "ExifLab Tester" },
                "Copyright ${after.exifRaw("exif-ifd0", 0x8298)}".takeIf { after.exifRaw("exif-ifd0", 0x8298) != "(c) 2026 ExifLab" },
                "dc:creator ${after.xmpRaw("dc:creator[1]")}".takeIf { after.xmpRaw("dc:creator[1]") != "ExifLab Tester" },
                "dc:rights ${after.xmpRaw("dc:rights[1]")}".takeIf { after.xmpRaw("dc:rights[1]") != "© 2026 ExifLab" },
                "By-line ${after.iptcRaw(80)}".takeIf { before.format == ImageFormat.Jpeg && after.iptcRaw(80) != "ExifLab Tester" },
            )
        },
        Scenario("catalog title", listOf(Editing.catalog.operationFor(Editing.catalog.field(FieldIds.TITLE)!!, "ExifLab sample"))) { _, after ->
            listOfNotNull("title".takeIf { Editing.catalog.currentValue(Editing.catalog.field(FieldIds.TITLE)!!, after) != "ExifLab sample" })
        },
    ) + StripCategory.entries.map { category ->
        Scenario("strip $category", listOf(EditOperation.Strip(setOf(category)))) { before, after -> stripProblems(category, before, after) }
    }

    private fun stripProblems(category: StripCategory, before: MetadataReport, after: MetadataReport): List<String> {
        val ids = after.directories.map { it.id }
        val xmp = after.xmpTags().map { it.name }
        return when (category) {
            StripCategory.Location -> listOfNotNull("location left".takeIf { "gps" in ids || xmp.any { it.startsWith("exif:GPS") } })
            StripCategory.DeviceIdentifiers ->
                listOf(0xA431, 0xA435, 0xA420).mapNotNull { tag -> "tag $tag left".takeIf { after.exifRaw("exif-subifd", tag) != null } } +
                    listOfNotNull("xmpMM ids left".takeIf { xmp.any { it == "xmpMM:DocumentID" || it == "xmpMM:InstanceID" } })
            StripCategory.DeviceInfo -> listOf(0x010F, 0x0110, 0x0131).mapNotNull { tag -> "tag $tag left".takeIf { after.exifRaw("exif-ifd0", tag) != null } }
            StripCategory.PersonNames -> listOfNotNull(
                "Artist left".takeIf { after.exifRaw("exif-ifd0", 0x013B) != null || after.exifRaw("exif-ifd0", 0x8298) != null },
                "dc:creator left".takeIf { xmp.any { it.startsWith("dc:creator") || it.startsWith("dc:rights") } },
            )
            StripCategory.People -> listOfNotNull("regions left".takeIf { xmp.any { it.startsWith("mwg-rs:") || it.startsWith("MP:") } })
            StripCategory.Dates -> listOfNotNull("capture time left: ${after.summary.capturedAt}".takeIf { exifDate(after) != null || after.exifRaw("exif-ifd0", 0x0132) != null })
            StripCategory.MakerNotes -> listOfNotNull("MakerNote left".takeIf { ids.any { it.startsWith("makernote") } })
            StripCategory.Thumbnail -> listOfNotNull("thumbnail left".takeIf { ids.any { it.startsWith("exif-thumbnail") } })
            StripCategory.EditHistory -> listOfNotNull("history left".takeIf { xmp.any { it.startsWith("xmpMM:") || it.startsWith("photoshop:History") } })
            StripCategory.All -> listOfNotNull(
                "left ${ids.filter { it.startsWith("exif-subifd") || it == "gps" || it.startsWith("makernote") || it.startsWith("xmp") || it == "iptc" }}"
                    .takeIf { ids.any { it.startsWith("exif-subifd") || it == "gps" || it.startsWith("makernote") || it.startsWith("xmp") || it == "iptc" } },
                "orientation ${after.summary.orientation}".takeIf { after.summary.orientation != before.summary.orientation },
                "color profile ${after.summary.colorProfile}".takeIf { after.summary.colorProfile != before.summary.colorProfile },
            )
        }
    }

    @Test fun everyOperationOnEveryWritableSample() {
        val directory = System.getenv("EXIFLAB_SAMPLES_DIR")?.let(::File)
        assumeTrue("EXIFLAB_SAMPLES_DIR is not set", directory != null && directory.isDirectory)
        val files = directory!!.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name.lowercase() }
        val problems = mutableListOf<String>()
        var edits = 0
        for (file in files) {
            val bytes = file.readBytes()
            val format = ImageFormat.sniff(bytes.copyOf(minOf(bytes.size, ImageFormat.SNIFF_LENGTH)), file.name)
            if (!MetadataWriting.writer.canWrite(format)) continue
            val before = try {
                Metadata.reader.read(ByteArrayImageSource(bytes, file.name))
            } catch (_: CorruptImageException) {
                continue
            }
            val outcomes = mutableListOf<String>()
            for (scenario in scenarios) {
                val label = "${file.name} / ${scenario.name}"
                try {
                    val plan = Editing.planner.plan(before, scenario.operations)
                    val written = write(bytes, file.name, plan)
                    val after = Metadata.reader.read(ByteArrayImageSource(written, file.name))
                    problems += scenario.check(before, after).map { "$label: $it" }
                    problems += unexpectedChanges(before, after, plan).map { "$label: $it" }
                    if (scenario.repeatable) {
                        val again = Editing.planner.plan(after, scenario.operations)
                        // A report with parse warnings may hide blocks, so removals are always planned for it; only the diff must be empty.
                        val stable = if (after.warnings.isEmpty()) again.changes.isEmpty else again.diff.isEmpty()
                        if (!stable) problems += "$label: not stable, planning again gives ${again.diff.map { "${it.label}: ${it.before} -> ${it.after}" }}"
                    }
                    outcomes += "${scenario.name}=${plan.diff.size}${if (plan.warnings.isEmpty()) "" else "!"}"
                    edits++
                } catch (e: Exception) {
                    problems += "$label: $e"
                }
            }
            println("${file.name} | ${format.displayName} | ${outcomes.joinToString(" ")}")
        }
        println("Planned and wrote $edits edits")
        assertThat(problems).isEmpty()
    }

    private fun write(bytes: ByteArray, name: String, plan: EditPlan): ByteArray {
        val out = ByteArrayOutputStream()
        val result = MetadataWriting.writer.write(ByteArrayImageSource(bytes, name), plan.changes, out)
        check(result.sourceImageDigest == result.outputImageDigest)
        return out.toByteArray()
    }

    /** Tags that changed although the plan did not address them. */
    private fun unexpectedChanges(before: MetadataReport, after: MetadataReport, plan: EditPlan): List<String> {
        val (exact, prefixes) = allowedKeys(before, plan)
        fun isAllowed(key: String) = key in exact || prefixes.any { key.startsWith(it) }
        val old = before.tagValues()
        val new = after.tagValues()
        return (old.keys + new.keys).filterNot(::isAllowed).filter { old[it] != new[it] }
            .map { "$it changed from ${old[it]?.take(60)} to ${new[it]?.take(60)}" }
    }

    /**
     * Keys and key prefixes the plan may change, including the tags the writer adds to blocks it
     * creates and what readers derive from changed tags.
     */
    private fun allowedKeys(before: MetadataReport, plan: EditPlan): Pair<Set<String>, Set<String>> {
        val exact = mutableSetOf<String>()
        val prefixes = mutableSetOf<String>()
        val changes = plan.changes
        fun directoryOf(ifd: ExifIfd) = when (ifd) {
            ExifIfd.Primary -> "exif-ifd0"
            ExifIfd.Exif -> "exif-subifd"
            ExifIfd.Gps -> "gps"
            ExifIfd.Interoperability -> "exif-interop"
            ExifIfd.Thumbnail -> "exif-thumbnail"
        }
        if (changes.exif.isNotEmpty()) {
            // A new Exif or GPS IFD gets the tags EXIF requires; a new IFD0 of a JPEG gets resolution tags.
            exact += listOf(36864, 37121, 40960, 40961, 40962, 40963).map { "exif-subifd:$it" } + "gps:0"
            exact += listOf(282, 283, 296, 531).map { "exif-ifd0:$it" }
        }
        for (change in changes.exif) {
            exact += "${directoryOf(change.ifd)}:${change.tag}"
            if (change is ExifChange.Remove && change.tag == 0x8825) prefixes += "gps:"
            // MakerNotes are decoded according to Make and Model.
            if (change.ifd == ExifIfd.Primary && (change.tag == 0x010F || change.tag == 0x0110)) {
                prefixes += "makernote"
                exact += "exif-subifd:37500"
            }
        }
        val registry = XMPMetaFactory.getSchemaRegistry()
        for (change in changes.xmp) {
            val prefix = registry.getNamespacePrefix(change.namespace)?.removeSuffix(":") ?: continue
            val name = when (change) {
                is XmpChange.SetProperty -> change.name
                is XmpChange.SetArray -> change.name
                is XmpChange.SetLocalizedText -> change.name
                is XmpChange.Remove -> change.name
                is XmpChange.RemoveNamespace -> null
            }?.substringAfter(':')
            // XMP may sit in "xmp" or "xmp-2" directories (extended XMP, several packets).
            for (directory in listOf("xmp", "xmp-2", "xmp-3")) {
                if (name == null) {
                    prefixes += "$directory:$prefix:"
                } else {
                    exact += "$directory:$prefix:$name"
                    prefixes += listOf("$directory:$prefix:$name[", "$directory:$prefix:$name/")
                }
            }
        }
        // The note linking an extended XMP packet changes whenever the packet is split again.
        exact += "xmp:xmpNote:HasExtendedXMP"
        for (change in changes.iptc) exact += "iptc:${(change.record shl 8) or change.dataset}"
        // Record version and character set, which the writer adds when it writes IPTC.
        if (changes.iptc.isNotEmpty()) exact += listOf("iptc:512", "iptc:346")
        for (block in changes.removeBlocks) when (block) {
            MetadataBlock.Exif -> prefixes += listOf("exif-", "gps:", "makernote", "image-subifd")
            MetadataBlock.MakerNote -> {
                prefixes += "makernote"
                exact += "exif-subifd:37500"
            }
            MetadataBlock.ExifThumbnail -> prefixes += "exif-thumbnail:"
            MetadataBlock.Xmp -> prefixes += "xmp"
            MetadataBlock.Iptc -> prefixes += "iptc:"
            MetadataBlock.PhotoshopResources -> prefixes += listOf("iptc:", "photoshop:")
            MetadataBlock.IccProfile -> prefixes += "icc:"
            MetadataBlock.Comments -> prefixes += listOf("jpeg-comment:", "png-text", "png-ztxt", "png-itxt")
            MetadataBlock.EmbeddedMedia -> prefixes += listOf("mpf", "xmp")
        }
        // A WebP that gains EXIF or XMP gets an extended (VP8X) header.
        if (before.format == ImageFormat.WebP) prefixes += "webp"
        return exact to prefixes
    }
}
