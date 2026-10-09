package io.github.fishpimp.exiflab.metadata

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import java.io.File
import java.io.InputStream
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in validation against real camera files, which are not committed. Point EXIFLAB_SAMPLES_DIR
 * at a directory of images (for example from github.com/drewnoakes/metadata-extractor-images) and
 * run `./gradlew :core:metadata:test --tests '*RealWorldSamplesTest*'`. One summary line per file
 * goes to the test's standard output.
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
