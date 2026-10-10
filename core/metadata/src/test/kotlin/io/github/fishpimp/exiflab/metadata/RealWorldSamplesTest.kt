package io.github.fishpimp.exiflab.metadata

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.Rational
import io.github.fishpimp.exiflab.metadata.write.ExifChange
import io.github.fishpimp.exiflab.metadata.write.ExifIfd
import io.github.fishpimp.exiflab.metadata.write.ExifValue
import io.github.fishpimp.exiflab.metadata.write.IptcChange
import io.github.fishpimp.exiflab.metadata.write.MetadataBlock
import io.github.fishpimp.exiflab.metadata.write.MetadataChanges
import io.github.fishpimp.exiflab.metadata.write.MetadataWriting
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.tagValues
import io.github.fishpimp.exiflab.metadata.write.XmpArrayKind
import io.github.fishpimp.exiflab.metadata.write.XmpChange
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.abs
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in validation against real camera files, which are not committed. Point EXIFLAB_SAMPLES_DIR
 * at a directory of images (for example from github.com/drewnoakes/metadata-extractor-images) and
 * run `./gradlew :core:metadata:test --tests '*RealWorldSamplesTest*'`. One summary line per file
 * goes to the test's standard output. With EXIFLAB_SAMPLES_OUT set, the files the writer test
 * produces are saved there for inspection with other tools (e.g. `exiftool -validate`).
 */
class RealWorldSamplesTest {
    private class FileSource(private val file: File) : ImageSource {
        override val fileName: String = file.name
        override val length: Long = file.length()
        override fun open(): InputStream = file.inputStream()
    }

    @Test fun readsEverySample() {
        val directory = System.getenv("EXIFLAB_SAMPLES_DIR")?.let(::File)
        assumeTrue("EXIFLAB_SAMPLES_DIR is not set", directory != null && directory.isDirectory)
        val files = directory!!.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name.lowercase() }
        assumeTrue("EXIFLAB_SAMPLES_DIR is empty", files.isNotEmpty())

        val problems = mutableListOf<String>()
        for (file in files) {
            val source = FileSource(file)
            val format = file.inputStream().use { ImageFormat.sniff(it.readNBytes(ImageFormat.SNIFF_LENGTH), file.name) }
            try {
                val report = DefaultMetadataReader().read(source)
                problems += sanityProblems(file.name, report)
                val preview = if (format.isRaw) DefaultPreviewExtractor().extract(source) else null
                // DNGs from phone cameras often carry no preview; Android decodes DNG natively instead.
                if (format.isRaw && format != ImageFormat.Dng && preview == null) problems += "${file.name}: RAW file without a preview"
                println(describe(file.name, report, preview))
            } catch (e: CorruptImageException) {
                // Deliberately broken fuzzing samples may be unreadable, but must fail cleanly.
                println("${file.name} | ${format.displayName} | unreadable: ${e.message}")
                if (!file.name.contains("Exception")) problems += "${file.name}: $e"
            } catch (e: Exception) {
                problems += "${file.name}: $e"
                println("${file.name} | ${format.displayName} | FAILED: $e")
            }
        }
        assertThat(problems).isEmpty()
    }

    /**
     * Applies a representative edit (EXIF tags in IFD0, the Exif IFD and GPS, XMP and IPTC) and a
     * full strip to every writable sample. Each write must keep the image data digest, show the
     * new values, and leave every other tag (MakerNotes included) exactly as it was.
     */
    @Test fun writesEveryWritableSample() {
        val directory = System.getenv("EXIFLAB_SAMPLES_DIR")?.let(::File)
        assumeTrue("EXIFLAB_SAMPLES_DIR is not set", directory != null && directory.isDirectory)
        val outDir = System.getenv("EXIFLAB_SAMPLES_OUT")?.takeIf { it.isNotBlank() }?.let(::File)?.also { it.mkdirs() }
        val files = directory!!.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name.lowercase() }
        val problems = mutableListOf<String>()
        var written = 0
        for (file in files) {
            val format = file.inputStream().use { ImageFormat.sniff(it.readNBytes(ImageFormat.SNIFF_LENGTH), file.name) }
            if (!MetadataWriting.writer.canWrite(format)) continue
            val source = FileSource(file)
            val before = try {
                DefaultMetadataReader().read(source)
            } catch (_: CorruptImageException) {
                continue
            }
            try {
                val (edited, skipped) = writeTo(source, EDIT)
                outDir?.let { File(it, "edited__" + file.name).writeBytes(edited) }
                val after = Metadata.reader.read(ByteArrayImageSource(edited, file.name))
                problems += editProblems(file.name, format, before, after)
                val (stripped, _) = writeTo(source, STRIP)
                outDir?.let { File(it, "stripped__" + file.name).writeBytes(stripped) }
                val strippedReport = Metadata.reader.read(ByteArrayImageSource(stripped, file.name))
                val left = strippedReport.directories.map { it.id }
                    .filter { id -> STRIPPED_IDS.any { id == it || id.startsWith("$it-") } || id.startsWith("makernote") }
                if (left.isNotEmpty()) problems += "${file.name}: strip left $left"
                written++
                println(
                    "${file.name} | ${format.displayName} | ${file.length()} -> ${edited.size} bytes | stripped ${stripped.size} | " +
                        "makernotes=${before.directories.count { it.id.startsWith("makernote") }} | skipped=$skipped",
                )
            } catch (e: Exception) {
                problems += "${file.name}: $e"
                println("${file.name} | ${format.displayName} | FAILED: $e")
            }
        }
        println("Wrote $written samples")
        assertThat(problems).isEmpty()
    }

    private fun writeTo(source: ImageSource, changes: MetadataChanges): Pair<ByteArray, List<String>> {
        val out = ByteArrayOutputStream()
        val result = MetadataWriting.writer.write(source, changes, out)
        val bytes = out.toByteArray()
        check(result.sourceImageDigest == result.outputImageDigest)
        check(MetadataWriting.digest.digest(ByteArrayImageSource(bytes, source.fileName)) == result.sourceImageDigest)
        return bytes to result.skipped
    }

    private fun editProblems(name: String, format: ImageFormat, before: MetadataReport, after: MetadataReport): List<String> = buildList {
        fun value(directory: String, tag: Int) = after.directories.firstOrNull { it.id == directory }?.tags?.firstOrNull { it.id == tag }?.rawValue
        if (value("exif-ifd0", 0x013B) != "ExifLab Tester") add("$name: Artist not written")
        if (value("exif-subifd", 0x9003) != "2021:02:03 04:05:06") add("$name: DateTimeOriginal not written")
        val location = after.location
        if (location == null || abs(location.latitude - (59 + 20 / 60.0)) > 1e-6 || abs(location.longitude - (18 + 3 / 60.0)) > 1e-6) {
            add("$name: GPS not written ($location)")
        }
        val xmp = after.directories.filter { it.id.startsWith("xmp") }.flatMap { it.tags }
        if (xmp.none { it.name == "xmp:Rating" && it.rawValue == "4" }) add("$name: XMP rating not written")
        val caption = after.directories.firstOrNull { it.id == "iptc" }?.tags?.firstOrNull { it.id == 0x0278 }?.rawValue
        if (format == ImageFormat.Jpeg && caption != "Written by ExifLab") add("$name: IPTC caption not written ($caption)")
        val old = before.tagValues()
        val new = after.tagValues()
        for (key in old.keys + new.keys) {
            if (isEdited(key)) continue
            // A new Exif IFD gets the ExifVersion it requires; a simple WebP gains a VP8X header.
            if (old[key] == null && (key == "exif-subifd:36864" || key.startsWith("webp"))) continue
            if (old[key] != new[key]) add("$name: $key changed from ${old[key]?.take(80)} to ${new[key]?.take(80)}")
        }
    }

    private fun isEdited(key: String): Boolean =
        key in EDITED_KEYS || key.startsWith("gps:") || key.startsWith("xmp") || key.startsWith("iptc:") || key.startsWith("photoshop:")

    private fun sanityProblems(name: String, report: MetadataReport): List<String> = buildList {
        val summary = report.summary
        if (report.tagCount == 0) add("$name: no tags")
        if ((summary.width ?: 1) <= 0 || (summary.height ?: 1) <= 0) add("$name: bad size ${summary.width}x${summary.height}")
        if (summary.orientation != null && summary.orientation !in 1..8) add("$name: bad orientation")
        if (summary.fNumber != null && summary.fNumber!! !in 0.5..128.0) add("$name: implausible f-number ${summary.fNumber}")
        if (summary.capturedAt != null && summary.capturedAt!!.year !in 1990..2030) add("$name: implausible date ${summary.capturedAt}")
        val keys = report.directories.flatMap { it.tags }.map { it.key }
        if (keys.size != keys.toSet().size) add("$name: duplicate tag keys")
        val ids = report.directories.map { it.id }
        if (ids.size != ids.toSet().size) add("$name: duplicate directory ids")
        report.location?.let { if (it.latitude !in -90.0..90.0 || it.longitude !in -180.0..180.0) add("$name: bad location $it") }
    }

    private companion object {
        val EDIT = MetadataChanges(
            exif = listOf(
                ExifChange.Set(ExifIfd.Primary, 0x013B, ExifValue.Ascii("ExifLab Tester")),
                ExifChange.Set(ExifIfd.Primary, 0x8298, ExifValue.Ascii("(c) ExifLab")),
                ExifChange.Set(ExifIfd.Exif, 0x9003, ExifValue.Ascii("2021:02:03 04:05:06")),
                ExifChange.Set(ExifIfd.Exif, 0x9011, ExifValue.Ascii("+01:00")),
                ExifChange.Set(ExifIfd.Gps, 0x0000, ExifValue.Bytes(listOf(2, 3, 0, 0))),
                ExifChange.Set(ExifIfd.Gps, 0x0001, ExifValue.Ascii("N")),
                ExifChange.Set(ExifIfd.Gps, 0x0002, ExifValue.Rationals(listOf(Rational(59, 1), Rational(20, 1), Rational(0, 1)))),
                ExifChange.Set(ExifIfd.Gps, 0x0003, ExifValue.Ascii("E")),
                ExifChange.Set(ExifIfd.Gps, 0x0004, ExifValue.Rationals(listOf(Rational(18, 1), Rational(3, 1), Rational(0, 1)))),
            ),
            xmp = listOf(
                XmpChange.SetProperty("http://ns.adobe.com/xap/1.0/", "xmp", "Rating", "4"),
                XmpChange.SetLocalizedText("http://purl.org/dc/elements/1.1/", "dc", "title", "ExifLab sample"),
                XmpChange.SetArray("http://purl.org/dc/elements/1.1/", "dc", "subject", listOf("exiflab", "test"), XmpArrayKind.Bag),
            ),
            iptc = listOf(IptcChange.Set(2, 120, listOf("Written by ExifLab")), IptcChange.Set(2, 25, listOf("exiflab"))),
        )

        val STRIP = MetadataChanges(removeBlocks = MetadataBlock.entries.toSet() - MetadataBlock.IccProfile)

        /** Directories a full strip must remove. */
        val STRIPPED_IDS = listOf("exif-ifd0", "exif-subifd", "exif-interop", "exif-thumbnail", "gps", "xmp", "iptc", "photoshop", "jpeg-comment")

        /** Artist, Copyright, DateTimeOriginal and OffsetTimeOriginal. */
        val EDITED_KEYS = setOf("exif-ifd0:315", "exif-ifd0:33432", "exif-subifd:36867", "exif-subifd:36881")
    }

    private fun describe(name: String, report: MetadataReport, preview: EmbeddedPreview?): String {
        val s = report.summary
        return listOf(
            name,
            report.format.displayName,
            "dirs=${report.directories.size} tags=${report.tagCount}",
            "${s.make} / ${s.model}",
            "lens=${s.lens}",
            "${s.width}x${s.height} o=${s.orientation}",
            "f=${s.fNumber} t=${s.exposureTime} iso=${s.iso} fl=${s.focalLengthMm}",
            "at=${s.capturedAt} ${s.utcOffset ?: ""}${s.utcOffsetSource?.let { "($it)" } ?: ""}",
            "loc=${report.locationStatus}",
            "icc=${s.colorProfile}",
            "sensitive=${report.sensitiveFindings.joinToString(",") { "${it.category}:${it.tagKeys.size}" }}",
            "preview=${preview?.let { "${it.width}x${it.height} ${it.jpegBytes.size / 1024}kB o=${it.orientation}" }}",
            "warnings=${report.warnings}",
            "ids=${report.directories.joinToString(",") { it.id }}",
        ).joinToString(" | ")
    }
}
