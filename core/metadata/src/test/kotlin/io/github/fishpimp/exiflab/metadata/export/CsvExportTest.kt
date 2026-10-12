package io.github.fishpimp.exiflab.metadata.export

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.fixtures.Photos
import org.junit.Test

class CsvExportTest {
    private fun csv(vararg reports: io.github.fishpimp.exiflab.metadata.model.MetadataReport, options: ExportOptions = ExportOptions(), safe: Boolean = true) =
        buildString {
            val writer = MetadataCsvWriter(this, options, spreadsheetSafe = safe)
            writer.writeHeader()
            reports.forEach { writer.writeReport(it) }
        }

    @Test fun headerAndOneRowPerTag() {
        val text = csv(ExportFixtures.report, safe = false)
        val rows = parseCsv(text)
        assertThat(rows.first()).containsExactlyElementsIn(MetadataCsvWriter.COLUMNS).inOrder()
        assertThat(rows).hasSize(1 + ExportFixtures.report.tagCount)
        assertThat(rows[1]).containsExactly("DSCF0042.JPG", "JPEG", "Exif IFD0", "Exif", "0x010F", "Make", "FUJIFILM", "FUJIFILM", "").inOrder()
        // Every row has every column, and lines end with CRLF.
        assertThat(rows.map { it.size }.toSet()).containsExactly(MetadataCsvWriter.COLUMNS.size)
        assertThat(text).endsWith("\r\n")
        assertThat(text.first()).isEqualTo('f')
    }

    @Test fun quotesCommasQuotesAndLineBreaks() {
        val rows = parseCsv(csv(ExportFixtures.report, safe = false))
        val artist = rows.single { it[5] == "Artist" }
        assertThat(artist[6]).isEqualTo("Åsa \"Fotograf\" Öberg, Malmö")
        assertThat(artist[8]).isEqualTo("PersonName")
        val description = rows.single { it[5] == "Image Description" }
        assertThat(description[6]).isEqualTo("Line one\nLine two, with comma\r\nand \"quotes\"")
        // XMP properties have no numeric id.
        assertThat(rows.single { it[5] == "dc:title" }[4]).isEmpty()
    }

    @Test fun escapeRules() {
        assertThat(MetadataCsvWriter.escape("plain")).isEqualTo("plain")
        assertThat(MetadataCsvWriter.escape("a,b")).isEqualTo("\"a,b\"")
        assertThat(MetadataCsvWriter.escape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"")
        assertThat(MetadataCsvWriter.escape("two\nlines")).isEqualTo("\"two\nlines\"")
        assertThat(MetadataCsvWriter.escape(" padded ")).isEqualTo("\" padded \"")
        assertThat(MetadataCsvWriter.escape("nul\u0000gone")).isEqualTo("nulgone")
        assertThat(MetadataCsvWriter.escape("")).isEmpty()
    }

    @Test fun spreadsheetSafeAddsBomAndDefusesFormulas() {
        val text = csv(ExportFixtures.report, safe = true)
        assertThat(text.first()).isEqualTo(MetadataCsvWriter.BYTE_ORDER_MARK)
        val rows = parseCsv(text.drop(1))
        assertThat(rows.single { it[5] == "dc:description" }[6]).isEqualTo("'=HYPERLINK(\"http://example.com\")")
        // Plain numbers and fractions stay as they are; "-1/3 EV" is not a number, so it is defused.
        assertThat(rows.single { it[5] == "Exposure Bias Value" }[7]).isEqualTo("-1/3")
        assertThat(rows.single { it[5] == "Exposure Bias Value" }[6]).isEqualTo("'-1/3 EV")
        assertThat(MetadataCsvWriter.escape("-12.5", spreadsheetSafe = true)).isEqualTo("-12.5")
        assertThat(MetadataCsvWriter.escape("+3", spreadsheetSafe = true)).isEqualTo("+3")
        assertThat(MetadataCsvWriter.escape("-", spreadsheetSafe = true)).isEqualTo("-")
        assertThat(MetadataCsvWriter.escape("@SUM(A1)", spreadsheetSafe = true)).isEqualTo("'@SUM(A1)")
        assertThat(MetadataCsvWriter.escape("-2+3+cmd|' /C calc'!A0", spreadsheetSafe = true)).isEqualTo("'-2+3+cmd|' /C calc'!A0")
    }

    @Test fun unicodeSurvives() {
        val rows = parseCsv(csv(ExportFixtures.report, safe = false))
        assertThat(rows.single { it[5] == "dc:title" }[6]).isEqualTo("東京タワー 📷 night")
        assertThat(rows.single { it[5] == "exif:Note" }[6]).isEqualTo("tab\there, nulend, control\u0001, separator ")
    }

    @Test fun severalFilesConcatenateRowsUnderOneHeader() {
        val rows = parseCsv(csv(ExportFixtures.report, ExportFixtures.bare, safe = false))
        assertThat(rows).hasSize(1 + ExportFixtures.report.tagCount + ExportFixtures.bare.tagCount)
        assertThat(rows.count { it == MetadataCsvWriter.COLUMNS }).isEqualTo(1)
        assertThat(rows.last()[0]).isEqualTo("screenshot, final.png")
        assertThat(rows.last()[1]).isEqualTo("PNG")
        assertThat(rows.last()[3]).isEqualTo("Container")
    }

    @Test fun rawValuesCanBeLeftOut() {
        val rows = parseCsv(csv(ExportFixtures.report, options = ExportOptions(includeRawValues = false), safe = false))
        assertThat(rows.drop(1).map { it[7] }.toSet()).containsExactly("")
        assertThat(rows.first()).contains("raw_value")
    }

    @Test fun redactionRemovesSensitiveValuesOnly() {
        val rows = parseCsv(csv(ExportFixtures.report, options = ExportOptions(redactSensitive = true), safe = false)).drop(1)
        rows.filter { it[8].isNotEmpty() }.forEach { row ->
            assertThat(row[6]).isEqualTo(REDACTED_VALUE)
            assertThat(row[7]).isEqualTo(REDACTED_VALUE)
        }
        assertThat(rows.count { it[6] == REDACTED_VALUE }).isEqualTo(3)
        assertThat(rows.single { it[5] == "Model" }[6]).isEqualTo("X-T5")
        val text = csv(ExportFixtures.report, options = ExportOptions(redactSensitive = true))
        assertThat(text).doesNotContain("Öberg")
        assertThat(text).doesNotContain("2112/100")
    }

    @Test fun realReportFromTheReader() {
        val report = Photos.read(Photos.jpeg(io.github.fishpimp.exiflab.metadata.fixtures.TiffBuilder().build(Photos.ifd0(Photos.exifIfd(), Photos.gpsIfd()))), "IMG_0001.jpg")
        val rows = parseCsv(csv(report, safe = false))
        assertThat(rows).hasSize(1 + report.tagCount)
        assertThat(rows.drop(1).map { it[0] }.toSet()).containsExactly("IMG_0001.jpg")
        assertThat(rows.any { it[8] == "Location" }).isTrue()
    }
}

/** A strict RFC 4180 reader for the tests: quoted fields, doubled quotes, CRLF records. */
fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (quoted) {
            if (c == '"') {
                if (i + 1 < text.length && text[i + 1] == '"') {
                    field.append('"')
                    i++
                } else {
                    quoted = false
                }
            } else {
                field.append(c)
            }
        } else {
            when (c) {
                '"' -> {
                    check(field.isEmpty()) { "Quote inside an unquoted field at $i" }
                    quoted = true
                }
                ',' -> {
                    row.add(field.toString())
                    field.clear()
                }
                '\r' -> {
                    check(text.getOrNull(i + 1) == '\n') { "Bare CR at $i" }
                    row.add(field.toString())
                    field.clear()
                    rows.add(row)
                    row = mutableListOf()
                    i++
                }
                '\n' -> error("Bare LF at $i")
                else -> field.append(c)
            }
        }
        i++
    }
    check(!quoted) { "Unterminated quote" }
    check(field.isEmpty() && row.isEmpty()) { "Last record has no line end" }
    return rows
}
