package io.github.fishpimp.exiflab.data.export

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.metadata.export.MetadataCsvWriter
import io.github.fishpimp.exiflab.screenshots.FakeReports
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ExportFileNamesTest {
    @Test fun singlePhotoDropsItsExtension() {
        assertThat(ExportFileNames.forPhoto("IMG_1234.JPG", ExportFormat.Pdf)).isEqualTo("IMG_1234-metadata.pdf")
        assertThat(ExportFileNames.forPhoto("DSCF4410.RAF", ExportFormat.Csv)).isEqualTo("DSCF4410-metadata.csv")
        assertThat(ExportFileNames.forPhoto("archive.tar.gz", ExportFormat.Json)).isEqualTo("archive.tar-metadata.json")
        assertThat(ExportFileNames.forPhoto("no extension", ExportFormat.Json)).isEqualTo("no extension-metadata.json")
    }

    @Test fun unusableNamesFallBack() {
        assertThat(ExportFileNames.forPhoto(null, ExportFormat.Pdf)).isEqualTo("photo-metadata.pdf")
        assertThat(ExportFileNames.forPhoto("   ", ExportFormat.Pdf)).isEqualTo("photo-metadata.pdf")
        assertThat(ExportFileNames.forPhoto(".jpg", ExportFormat.Pdf)).isEqualTo("jpg-metadata.pdf")
    }

    @Test fun reservedCharactersAreReplaced() {
        assertThat(ExportFileNames.forPhoto("a/b\\c:d*e?f\"g<h>i|j.png", ExportFormat.Csv)).isEqualTo("a_b_c_d_e_f_g_h_i_j-metadata.csv")
        assertThat(ExportFileNames.forPhoto("tab\there\n.jpg", ExportFormat.Csv)).isEqualTo("tab_here_-metadata.csv")
    }

    @Test fun longNamesAreShortenedWithoutBreakingEmoji() {
        val name = "a".repeat(79) + "📷" + "b".repeat(50) + ".jpg"
        val suggested = ExportFileNames.forPhoto(name, ExportFormat.Pdf)
        assertThat(suggested).isEqualTo("a".repeat(79) + "-metadata.pdf")
    }

    @Test fun unicodeNamesAreKept() {
        assertThat(ExportFileNames.forPhoto("Midsommar på Öland.heic", ExportFormat.Pdf)).isEqualTo("Midsommar på Öland-metadata.pdf")
    }

    @Test fun selections() {
        val today = LocalDate.of(2026, 10, 12)
        assertThat(ExportFileNames.forSelection(null, ExportFormat.Csv, today)).isEqualTo("photos-metadata-2026-10-12.csv")
        assertThat(ExportFileNames.forSelection("Camera", ExportFormat.Json, today)).isEqualTo("Camera-metadata.json")
        assertThat(ExportFileNames.forSelection("  ", ExportFormat.Pdf, today)).isEqualTo("photos-metadata-2026-10-12.pdf")
    }
}

class ExportSettingsTest {
    @Test fun optionsThatDoNotApplyAreSwitchedOff() {
        val csv = ExportSettings(format = ExportFormat.Csv, includeMap = true, spreadsheetFriendly = true).effective
        assertThat(csv.includeMap).isFalse()
        assertThat(csv.spreadsheetFriendly).isTrue()

        val pdf = ExportSettings(format = ExportFormat.Pdf, includeMap = true, spreadsheetFriendly = true).effective
        assertThat(pdf.includeMap).isTrue()
        assertThat(pdf.spreadsheetFriendly).isFalse()

        // Redacting removes the location, so there is nothing to map.
        val redacted = ExportSettings(format = ExportFormat.Pdf, includeMap = true, redactSensitive = true)
        assertThat(redacted.mapApplies).isFalse()
        assertThat(redacted.effective.includeMap).isFalse()
    }

    @Test fun choicesSurviveSwitchingFormats() {
        val settings = ExportSettings(format = ExportFormat.Csv, includeMap = false, spreadsheetFriendly = false)
        val back = settings.copy(format = ExportFormat.Pdf).copy(format = ExportFormat.Csv)
        assertThat(back).isEqualTo(settings)
    }

    @Test fun defaultsDependOnHowManyPhotos() {
        assertThat(ExportSettings.defaultFor(1).format).isEqualTo(ExportFormat.Pdf)
        assertThat(ExportSettings.defaultFor(12).format).isEqualTo(ExportFormat.Csv)
        assertThat(ExportSettings.defaultFor(1).includeRawValues).isTrue()
        assertThat(ExportSettings.defaultFor(1).redactSensitive).isFalse()
    }

    @Test fun coreOptionsFollowTheSwitches() {
        val options = ExportSettings(includeRawValues = false, redactSensitive = true).coreOptions
        assertThat(options.includeRawValues).isFalse()
        assertThat(options.redactSensitive).isTrue()
    }
}

class MetadataExporterTest {
    private val phone = FakeReports.phoneRef
    private val raw = FakeReports.rawRef
    private val broken = PhotoRef("content://fake/broken.jpg", "broken.jpg", "image/jpeg", 10, null, PhotoOrigin.Folder, writable = true)

    private val reports = mapOf(phone.uri to FakeReports.phoneJpeg, raw.uri to FakeReports.cameraRaw)
    private val noPdf = PdfExport { _, _, _, _, _ -> error("not a PDF") }

    private fun exporter(read: ReportReader = { ref -> reports[ref.uri] ?: throw IOException("unreadable") }) = MetadataExporter(
        readReport = read,
        pdf = noPdf,
        appVersion = "0.1.0",
        now = { OffsetDateTime.of(2026, 10, 12, 9, 0, 0, 0, ZoneOffset.ofHours(2)) },
    )

    @Test fun csvHasOneRowPerTagOfEveryPhoto() = runTest {
        val out = ByteArrayOutputStream()
        val progress = mutableListOf<ExportProgress>()
        val result = exporter().export(listOf(phone, raw), ExportSettings(format = ExportFormat.Csv), out) { progress += it }
        assertThat(result).isEqualTo(ExportResult(exported = 2, failed = emptyList()))
        val text = out.toString(Charsets.UTF_8)
        assertThat(text.first()).isEqualTo(MetadataCsvWriter.BYTE_ORDER_MARK)
        val lines = text.drop(1).split("\r\n").filter { it.isNotEmpty() }
        assertThat(lines.first()).isEqualTo(MetadataCsvWriter.COLUMNS.joinToString(","))
        assertThat(lines.count { it.startsWith(FakeReports.PHONE_NAME + ",") }).isEqualTo(FakeReports.phoneJpeg.tagCount)
        assertThat(progress.last()).isEqualTo(ExportProgress(2, 2))
    }

    @Test fun csvWithoutSpreadsheetOptionHasNoByteOrderMark() = runTest {
        val out = ByteArrayOutputStream()
        exporter().export(listOf(phone), ExportSettings(format = ExportFormat.Csv, spreadsheetFriendly = false), out)
        assertThat(out.toByteArray().take(4).map { it.toInt().toChar() }.joinToString("")).isEqualTo("file")
    }

    @Test fun jsonNamesPhotosAndLeavesOutUnreadableOnes() = runTest {
        val out = ByteArrayOutputStream()
        val result = exporter().export(listOf(phone, broken, raw), ExportSettings(format = ExportFormat.Json), out)
        assertThat(result.exported).isEqualTo(2)
        assertThat(result.failed).containsExactly("broken.jpg")
        val root = Json.parseToJsonElement(out.toString(Charsets.UTF_8)).jsonObject
        assertThat(root.getValue("exiflab").jsonObject.getValue("version").jsonPrimitive.content).isEqualTo("0.1.0")
        assertThat(root.getValue("exiflab").jsonObject.getValue("exportedAt").jsonPrimitive.content).isEqualTo("2026-10-12T09:00:00+02:00")
        val names = root.getValue("files").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertThat(names).containsExactly(FakeReports.PHONE_NAME, FakeReports.RAW_NAME).inOrder()
    }

    @Test fun redactionReachesTheFile() = runTest {
        val out = ByteArrayOutputStream()
        exporter().export(listOf(raw), ExportSettings(format = ExportFormat.Json, redactSensitive = true), out)
        val text = out.toString(Charsets.UTF_8)
        assertThat(text).contains("\"redacted\": true")
        assertThat(text).doesNotContain("Maja Lindqvist")
    }

    @Test fun pdfGoesToThePdfWriterWithEffectiveSettings() = runTest {
        var seen: ExportSettings? = null
        val exporter = MetadataExporter(
            readReport = { error("unused") },
            pdf = PdfExport { photos, settings, _, _, _ ->
                seen = settings
                ExportResult(photos.size, emptyList())
            },
            appVersion = "0.1.0",
        )
        val palette = io.github.fishpimp.exiflab.data.export.pdf.PdfPalette(
            com.materialkolor.dynamicColorScheme(androidx.compose.ui.graphics.Color(0xFF1E8C93), isDark = false),
            com.materialkolor.dynamicColorScheme(androidx.compose.ui.graphics.Color(0xFFD9480F), isDark = false),
        )
        val result = exporter.export(listOf(phone), ExportSettings(format = ExportFormat.Pdf, redactSensitive = true, includeMap = true), ByteArrayOutputStream(), palette)
        assertThat(result.exported).isEqualTo(1)
        assertThat(seen!!.includeMap).isFalse()
        assertThat(seen!!.redactSensitive).isTrue()
    }

    @Test fun cancellingStopsBetweenPhotos() = runTest {
        var reads = 0
        val job = Job()
        val exporter = exporter { ref ->
            reads++
            if (reads == 2) job.cancel()
            reports[ref.uri] ?: FakeReports.phoneJpeg
        }
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking(job) {
                exporter.export(List(5) { phone }, ExportSettings(format = ExportFormat.Csv), ByteArrayOutputStream())
            }
        }
        assertThat(reads).isEqualTo(2)
    }

    @Test fun displayNamePrefersTheProviderName() {
        assertThat(MetadataExporter.displayName(phone, FakeReports.cameraRaw)).isEqualTo(FakeReports.PHONE_NAME)
        assertThat(MetadataExporter.displayName(phone.copy(displayName = null), FakeReports.cameraRaw)).isEqualTo(FakeReports.RAW_NAME)
        assertThat(MetadataExporter.displayName(broken.copy(displayName = null), null)).isEqualTo("broken.jpg")
    }
}
