package io.github.fishpimp.exiflab.data.export.pdf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Every character is half its font size wide, so line breaks are easy to predict. */
private val fakeMeasurer = PdfTextMeasurer { text, style -> text.length * style.size * 0.5f }

class PdfTextWrapperTest {
    private val wrapper = PdfTextWrapper(fakeMeasurer)
    private val style = PdfTextStyle(PdfFont.Body, 10f, PdfColor.Text, lineHeight = 12f)

    // 10 pt text at 5 pt per character: 50 pt hold 10 characters.

    @Test fun shortTextStaysOnOneLine() {
        assertThat(wrapper.wrap("Short", style, 50f)).containsExactly("Short")
        assertThat(wrapper.wrap("", style, 50f)).containsExactly("")
    }

    @Test fun breaksAtSpaces() {
        assertThat(wrapper.wrap("one two three four", style, 50f)).containsExactly("one two", "three four").inOrder()
    }

    @Test fun breaksAfterPunctuationInFileNames() {
        assertThat(wrapper.wrap("PXL_20260614_174207123.jpg", style, 50f)).containsExactly("PXL_", "20260614_", "174207123.", "jpg").inOrder()
    }

    @Test fun breaksInsideWordsOnlyWhenNothingElseFits() {
        assertThat(wrapper.wrap("0123456789ABCDEFGHIJKLMN", style, 50f)).containsExactly("0123456789", "ABCDEFGHIJ", "KLMN").inOrder()
    }

    @Test fun keepsLineBreaks() {
        assertThat(wrapper.wrap("a\r\nb\nc\rd", style, 50f)).containsExactly("a", "b", "c", "d").inOrder()
        assertThat(wrapper.wrap("a\n\nb", style, 50f)).containsExactly("a", "", "b").inOrder()
    }

    @Test fun ellipsizesBeyondMaxLines() {
        val lines = wrapper.wrap("one two three four five six", style, 50f, maxLines = 2)
        assertThat(lines).hasSize(2)
        assertThat(lines.last()).endsWith(PdfTextWrapper.ELLIPSIS)
        lines.forEach { assertThat(fakeMeasurer.width(it, style)).isAtMost(50f) }
    }

    @Test fun cutsHugeValues() {
        val lines = wrapper.wrap("x".repeat(100_000), style, 500f)
        // 4000 characters at 100 per line, the last ending in an ellipsis.
        assertThat(lines).hasSize(PdfTextWrapper.MAX_CHARS / 100)
        assertThat(lines.last()).endsWith(PdfTextWrapper.ELLIPSIS)
    }

    @Test fun neverSplitsSurrogatePairs() {
        val emoji = "📷"
        val lines = wrapper.wrap(emoji.repeat(12), style, 50f)
        lines.forEach { line ->
            assertThat(Character.isHighSurrogate(line.last())).isFalse()
            assertThat(Character.isLowSurrogate(line.first())).isFalse()
        }
        assertThat(lines.joinToString("")).isEqualTo(emoji.repeat(12))
    }
}

class PdfReportLayoutTest {
    private val layout = PdfReportLayout(fakeMeasurer)
    private val page = layout.page

    private fun table(title: String, rows: Int, value: (Int) -> String = { "Value $it" }) = PdfTable(
        title = title,
        detail = "$rows tags",
        continued = "(continued)",
        columns = listOf(PdfColumn("Tag", 1.3f), PdfColumn("ID", 0.5f, PdfStyles.CellMuted), PdfColumn("Value", 2f), PdfColumn("Raw value", 1.5f, PdfStyles.CellMono)),
        rows = List(rows) { listOf("Tag $it", "0x%04X".format(it), value(it), "$it/1") },
    )

    private fun section(
        tables: List<PdfTable> = listOf(table("Exif IFD0", 10)),
        map: PdfMap = PdfMap.Panel("Map not included (offline)"),
        coordinates: String? = "59.326469, 18.071853",
    ) = PdfPhotoSection(
        overline = "Metadata report",
        title = "PXL_20260614_174207123.jpg",
        subtitle = "JPEG · 3.4 MB",
        thumbnailAspect = 4f / 3f,
        camera = "Google Pixel 8 Pro",
        lens = "Pixel 8 Pro back camera 6.9mm f/1.68",
        exposure = listOf(PdfStat("f/1.7", "Aperture"), PdfStat("1/467 s", "Shutter speed"), PdfStat("ISO 49", "Sensitivity")),
        facts = listOf(PdfFact("Taken", "Jun 14, 2026, 7:42:07 PM\nUTC+02:00"), PdfFact("Resolution", "4080 × 3072")),
        privacy = PdfPrivacy("Privacy", "This photo carries data that can identify people.", listOf(PdfFinding("Location", "11 tags"))),
        location = PdfLocationBlock("Location", coordinates, "59°19′35.3″N 18°04′18.7″E", listOf(PdfFact("Altitude", "18 m")), if (coordinates == null) PdfMap.None else map, if (coordinates == null) "No location" else null),
        tagsTitle = "All metadata",
        tables = tables,
        warnings = null,
    )

    private val PdfPageLayout.textOps get() = ops.filterIsInstance<PdfOp.Text>()

    @Test fun everythingStaysInsideTheContentArea() {
        val pages = layout.layoutPhoto(section(tables = listOf(table("Exif IFD0", 80), table("XMP", 40))))
        pages.forEach { pageLayout ->
            pageLayout.ops.forEach { op ->
                when (op) {
                    is PdfOp.Text -> {
                        assertThat(op.x).isAtLeast(page.contentLeft - 0.01f)
                        assertThat(op.x + fakeMeasurer.width(op.text, op.style)).isAtMost(page.contentRight + 0.01f)
                        assertThat(op.baseline).isAtMost(page.contentBottom)
                        assertThat(op.baseline).isAtLeast(page.contentTop)
                    }
                    is PdfOp.Rect -> assertThat(op.bottom).isAtMost(page.contentBottom + 0.01f)
                    else -> Unit
                }
            }
        }
    }

    @Test fun coverComesFirstWithTitleCameraAndThumbnail() {
        val first = layout.layoutPhoto(section()).first()
        val texts = first.textOps.map { it.text }
        assertThat(texts.first()).isEqualTo("METADATA REPORT")
        assertThat(texts).containsAtLeast("PXL_20260614_174207123.jpg", "Google Pixel 8 Pro", "f/1.7", "1/467 s", "ISO 49", "Privacy", "Location")
        val thumbnail = first.ops.filterIsInstance<PdfOp.Image>().single { it.image == PdfImage.Thumbnail }
        // 4:3 photo in the square slot: full width, three quarters high.
        assertThat(thumbnail.right - thumbnail.left).isWithin(0.01f).of(PdfReportLayout.THUMBNAIL_SIZE)
        assertThat(thumbnail.bottom - thumbnail.top).isWithin(0.01f).of(PdfReportLayout.THUMBNAIL_SIZE * 0.75f)
    }

    @Test fun longTablesContinueWithTheirTitleAndHeaderRepeated() {
        val pages = layout.layoutPhoto(section(tables = listOf(table("Exif SubIFD", 150))))
        assertThat(pages.size).isAtLeast(3)
        // Every page after the first that holds rows starts with the table title marked as continued, then the header.
        pages.drop(1).forEach { pageLayout ->
            val texts = pageLayout.textOps.map { it.text }
            assertThat(texts.first()).isEqualTo("PXL_20260614_174207123.jpg")
            if (texts.any { it.startsWith("Tag ") }) {
                assertThat(texts).contains("Exif SubIFD (continued)")
                assertThat(texts).containsAtLeast("TAG", "ID", "VALUE", "RAW VALUE").inOrder()
            }
        }
        // No row is lost or repeated.
        val rows = pages.flatMap { it.textOps.map { text -> text.text } }.filter { it.matches(Regex("Tag \\d+")) }
        assertThat(rows).containsExactlyElementsIn(List(150) { "Tag $it" }).inOrder()
    }

    @Test fun longValuesWrapInsideTheirCell() {
        val long = "word ".repeat(60).trim()
        val pages = layout.layoutPhoto(section(tables = listOf(table("XMP", 3) { if (it == 1) long else "short" })))
        val valueLines = pages.flatMap { it.textOps }.filter { it.text.startsWith("word") }
        assertThat(valueLines.size).isGreaterThan(3)
        assertThat(valueLines.map { it.x }.toSet()).hasSize(1)
        // The next row starts below the last line of the wrapped value.
        val nextRow = pages.flatMap { it.textOps }.single { it.text == "Tag 2" }
        assertThat(nextRow.baseline).isGreaterThan(valueLines.maxOf { it.baseline })
    }

    @Test fun mapAndPanelTakeTheSameRoom() {
        val withMap = layout.layoutPhoto(section(map = PdfMap.Snapshot("OpenFreeMap © OpenMapTiles © OpenStreetMap contributors"), tables = listOf(table("Exif", 120))))
        val withPanel = layout.layoutPhoto(section(map = PdfMap.Panel("Map not included (offline)"), tables = listOf(table("Exif", 120))))
        assertThat(withMap.size).isEqualTo(withPanel.size)
        val mapBaseline = withMap.flatMap { it.textOps }.first { it.text == "Tag 0" }.baseline
        val panelBaseline = withPanel.flatMap { it.textOps }.first { it.text == "Tag 0" }.baseline
        assertThat(mapBaseline).isWithin(0.01f).of(panelBaseline)
        assertThat(withMap.first().ops.filterIsInstance<PdfOp.Image>().map { it.image }).contains(PdfImage.Map)
        assertThat(withPanel.flatMap { it.textOps }.map { it.text }).contains("Map not included (offline)")
    }

    @Test fun noLocationShowsTheMessageInstead() {
        val texts = layout.layoutPhoto(section(coordinates = null)).flatMap { it.textOps }.map { it.text }
        assertThat(texts).contains("No location")
        assertThat(texts).doesNotContain("59.326469, 18.071853")
    }

    @Test fun footerHasCreditAndPageNumber() {
        val footer = layout.footer("Created with ExifLab - Oct 12, 2026", "Page 2 of 5").filterIsInstance<PdfOp.Text>()
        assertThat(footer.map { it.text }).containsExactly("Created with ExifLab - Oct 12, 2026", "Page 2 of 5").inOrder()
        val pageLabel = footer.last()
        assertThat(pageLabel.x + fakeMeasurer.width(pageLabel.text, pageLabel.style)).isWithin(0.01f).of(page.contentRight)
        assertThat(footer.first().baseline).isGreaterThan(page.contentBottom)
        assertThat(footer.first().baseline).isLessThan(page.height - page.margin + 0.01f)
    }

    @Test fun summaryListsEveryPhoto() {
        val summary = PdfSummary(
            overline = "Metadata report",
            title = "120 photos",
            subtitle = "Exported Oct 12, 2026",
            stats = listOf(PdfStat("120", "Photos"), PdfStat("80", "With privacy findings")),
            table = PdfTable("", "", "(continued)", listOf(PdfColumn("#", 0.4f), PdfColumn("File", 2f), PdfColumn("Page", 0.5f)), List(120) { listOf("${it + 1}", "IMG_$it.jpg", "${it + 3}") }),
            notIncluded = PdfNotes("Not included", listOf("broken.jpg")),
        )
        val pages = layout.layoutSummary(summary)
        assertThat(pages.size).isAtLeast(2)
        val texts = pages.flatMap { it.textOps }.map { it.text }
        assertThat(texts.filter { it.startsWith("IMG_") }).hasSize(120)
        assertThat(texts).contains("broken.jpg")
        // The header row repeats on the next page, without a title.
        assertThat(pages[1].textOps.map { it.text }).containsAtLeast("#", "FILE", "PAGE").inOrder()
    }

    @Test fun fitKeepsTheAspectRatio() {
        assertThat(PdfReportLayout.fit(2f, 100f, 100f)).isEqualTo(100f to 50f)
        assertThat(PdfReportLayout.fit(0.5f, 100f, 100f)).isEqualTo(50f to 100f)
        assertThat(PdfReportLayout.fit(Float.NaN, 100f, 80f)).isEqualTo(100f to 80f)
    }
}
