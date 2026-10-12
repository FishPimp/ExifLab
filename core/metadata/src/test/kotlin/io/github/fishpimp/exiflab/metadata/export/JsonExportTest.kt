package io.github.fishpimp.exiflab.metadata.export

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.PhotoSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class JsonExportTest {
    private fun export(vararg reports: MetadataReport, options: ExportOptions = ExportOptions()): String = buildString {
        val writer = MetadataJsonWriter(this, ExportFixtures.info, options)
        writer.begin()
        reports.forEach { writer.writeReport(it) }
        writer.end()
    }

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val JsonObject.files: JsonArray get() = getValue("files").jsonArray
    private fun JsonObject.obj(name: String) = getValue(name).jsonObject
    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

    @Test fun headerDescribesTheExport() {
        val header = parse(export(ExportFixtures.report)).obj("exiflab")
        assertThat(header.string("version")).isEqualTo("0.1.0")
        assertThat(header.getValue("schema").jsonPrimitive.int).isEqualTo(MetadataJsonWriter.SCHEMA_VERSION)
        assertThat(header.string("exportedAt")).isEqualTo("2026-10-12T14:03:22+02:00")
        assertThat(header.getValue("redacted").jsonPrimitive.boolean).isFalse()
        assertThat(header.getValue("rawValues").jsonPrimitive.boolean).isTrue()
    }

    @Test fun roundTripsEveryTag() {
        val report = ExportFixtures.report
        val file = parse(export(report)).files.single().jsonObject
        assertThat(file.string("name")).isEqualTo("DSCF0042.JPG")
        assertThat(file.getValue("size").jsonPrimitive.long).isEqualTo(8_388_608)
        assertThat(file.string("format")).isEqualTo("JPEG")
        assertThat(file.string("mimeType")).isEqualTo("image/jpeg")
        val directories = file.getValue("directories").jsonArray.map { it.jsonObject }
        assertThat(directories.map { it.string("id") }).containsExactly("exif-ifd0", "gps", "xmp").inOrder()
        // Every value, including quotes, line breaks, control characters, NUL and emoji, survives exactly.
        val parsedTags = directories.flatMap { directory -> directory.getValue("tags").jsonArray.map { it.jsonObject } }
        val originalTags = report.directories.flatMap { it.tags }
        assertThat(parsedTags).hasSize(originalTags.size)
        parsedTags.zip(originalTags).forEach { (parsed, original) ->
            assertThat(parsed.string("key")).isEqualTo(original.key)
            assertThat(parsed.string("name")).isEqualTo(original.name)
            assertThat(parsed.string("value")).isEqualTo(original.displayValue)
            assertThat(parsed.string("raw")).isEqualTo(original.rawValue)
            if (original.id == null) {
                assertThat(parsed.getValue("id")).isEqualTo(JsonNull)
                assertThat(parsed.getValue("hexId")).isEqualTo(JsonNull)
            } else {
                assertThat(parsed.getValue("id").jsonPrimitive.int).isEqualTo(original.id)
                assertThat(parsed.string("hexId")).isEqualTo(original.hexId)
            }
            val sensitive = parsed.getValue("sensitive")
            if (original.sensitivity == null) assertThat(sensitive).isEqualTo(JsonNull) else assertThat(sensitive.jsonPrimitive.content).isEqualTo(original.sensitivity!!.name)
        }
        assertThat(file.getValue("warnings").jsonArray.map { it.jsonPrimitive.content }).containsExactly("MakerNote truncated at offset 0x1F00")
    }

    @Test fun summaryWithOffsetAndLocation() {
        val file = parse(export(ExportFixtures.report)).files.single().jsonObject
        val summary = file.obj("summary")
        assertThat(summary.string("camera")).isEqualTo("FUJIFILM X-T5")
        assertThat(summary.string("lens")).isEqualTo("XF23mmF1.4 R LM WR")
        assertThat(summary.getValue("fNumber").jsonPrimitive.double).isEqualTo(1.4)
        assertThat(summary.string("exposureTime")).isEqualTo("1/250")
        assertThat(summary.getValue("exposureTimeSeconds").jsonPrimitive.double).isEqualTo(0.004)
        assertThat(summary.getValue("iso").jsonPrimitive.int).isEqualTo(125)
        // Whole numbers are written without a fraction.
        assertThat(summary.getValue("focalLengthMm").jsonPrimitive.content).isEqualTo("23")
        assertThat(summary.getValue("flashFired").jsonPrimitive.boolean).isFalse()
        assertThat(summary.string("capturedAt")).isEqualTo("2026-06-14T17:42:00+02:00")
        assertThat(summary.string("utcOffset")).isEqualTo("+02:00")
        assertThat(summary.string("utcOffsetSource")).isEqualTo("ExifOffsetTime")
        val location = file.obj("location")
        assertThat(location.getValue("lat").jsonPrimitive.double).isEqualTo(55.6059)
        assertThat(location.getValue("lon").jsonPrimitive.double).isEqualTo(13.002)
        assertThat(location.getValue("alt").jsonPrimitive.double).isEqualTo(12.5)
        assertThat(location.string("gpsTime")).isEqualTo("2026-06-14T15:42:00Z")
        assertThat(file.string("locationStatus")).isEqualTo("Present")
        val sensitive = file.getValue("sensitive").jsonArray.map { it.jsonObject }
        assertThat(sensitive.map { it.string("category") }).containsExactly("Location", "PersonName").inOrder()
        assertThat(sensitive.first().getValue("tags").jsonArray.map { it.jsonPrimitive.content }).containsExactly("gps:0x0002", "gps:0x0004")
    }

    @Test fun summaryKeysAreAlwaysPresent() {
        val files = parse(export(ExportFixtures.report, ExportFixtures.bare)).files.map { it.jsonObject }
        assertThat(files).hasSize(2)
        val full = files[0].obj("summary").keys
        val bare = files[1].obj("summary")
        assertThat(bare.keys).isEqualTo(full)
        assertThat(bare.getValue("camera")).isEqualTo(JsonNull)
        assertThat(bare.getValue("utcOffset")).isEqualTo(JsonNull)
        // Without an offset the capture time is local time, seconds included.
        assertThat(bare.string("capturedAt")).isEqualTo("2025-01-02T03:04:05")
        assertThat(files[1].getValue("location")).isEqualTo(JsonNull)
        assertThat(files[1].getValue("size")).isEqualTo(JsonNull)
        assertThat(files[1].string("name")).isEqualTo("screenshot, final.png")
    }

    @Test fun utcCaptureTimeKeepsNumericOffset() {
        val summary = PhotoSummary(capturedAt = LocalDateTime.of(2026, 1, 1, 0, 0, 0), utcOffset = ZoneOffset.UTC)
        assertThat(MetadataJsonWriter.capturedAtIso(summary)).isEqualTo("2026-01-01T00:00:00+00:00")
        assertThat(MetadataJsonWriter.capturedAtIso(summary.copy(utcOffset = ZoneOffset.ofHoursMinutes(-3, -30)))).isEqualTo("2026-01-01T00:00:00-03:30")
    }

    @Test fun redactionDropsLocationAndSensitiveValues() {
        val text = export(ExportFixtures.report, options = ExportOptions(redactSensitive = true))
        val root = parse(text)
        assertThat(root.obj("exiflab").getValue("redacted").jsonPrimitive.boolean).isTrue()
        val file = root.files.single().jsonObject
        assertThat(file.getValue("location")).isEqualTo(JsonNull)
        val tags = file.getValue("directories").jsonArray.flatMap { it.jsonObject.getValue("tags").jsonArray.map { tag -> tag.jsonObject } }
        tags.filter { it.getValue("sensitive") != JsonNull }.forEach {
            assertThat(it.string("value")).isEqualTo(REDACTED_VALUE)
            assertThat(it.string("raw")).isEqualTo(REDACTED_VALUE)
        }
        assertThat(text).doesNotContain("Öberg")
        assertThat(text).doesNotContain("55.6059")
        assertThat(text).doesNotContain("2112/100")
        // Findings still say what was there.
        assertThat(file.getValue("sensitive").jsonArray).hasSize(2)
    }

    @Test fun rawValuesCanBeLeftOut() {
        val root = parse(export(ExportFixtures.report, options = ExportOptions(includeRawValues = false)))
        val tags = root.files.single().jsonObject.getValue("directories").jsonArray
            .flatMap { it.jsonObject.getValue("tags").jsonArray.map { tag -> tag.jsonObject } }
        assertThat(tags.none { "raw" in it }).isTrue()
        assertThat(root.obj("exiflab").getValue("rawValues").jsonPrimitive.boolean).isFalse()
    }

    @Test fun escapingIsStrictJson() {
        val quoted = JsonOut.quoted("a\"b\\c\n\r\t\b\u000C\u0000\u001F\u007F  é😀")
        assertThat(quoted).isEqualTo("\"a\\\"b\\\\c\\n\\r\\t\\b\\f\\u0000\\u001f\\u007f\\u2028\\u2029é😀\"")
        assertThat(Json.parseToJsonElement(quoted).jsonPrimitive.content).isEqualTo("a\"b\\c\n\r\t\b\u000C\u0000\u001F\u007F  é😀")
    }

    @Test fun emptyExportIsValid() {
        val root = parse(export())
        assertThat(root.files).isEmpty()
    }

    @Test fun nonFiniteNumbersBecomeNull() {
        assertThat(buildString { JsonOut(this).beginArray().value(Double.NaN).value(Double.POSITIVE_INFINITY).value(0.1).endArray() })
            .isEqualTo("[\n  null,\n  null,\n  0.1\n]")
    }
}
