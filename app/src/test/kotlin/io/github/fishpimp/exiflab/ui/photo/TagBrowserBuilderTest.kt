package io.github.fishpimp.exiflab.ui.photo

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory
import io.github.fishpimp.exiflab.screenshots.FakeReports
import org.junit.Test

class TagBrowserBuilderTest {
    private val phone = FakeReports.phoneJpeg
    private val raw = FakeReports.cameraRaw

    private fun build(
        report: MetadataReport = phone,
        query: String = "",
        mode: ValueMode = ValueMode.Readable,
        filter: SensitivityCategory? = null,
        collapsed: Set<String> = emptySet(),
    ) = TagBrowserBuilder.build(report, query, mode, filter, collapsed)

    private fun TagBrowser.tags() = rows.filterIsInstance<TagRow>()
    private fun TagBrowser.sections() = rows.filterIsInstance<SectionHeaderRow>()

    @Test
    fun `without a query every directory and tag is listed in order`() {
        val browser = build()
        assertThat(browser.sections().map { it.sectionId }).containsExactlyElementsIn(phone.directories.map { it.id }).inOrder()
        assertThat(browser.tags()).hasSize(phone.tagCount)
        assertThat(browser.matchingTags).isEqualTo(phone.tagCount)
        assertThat(browser.isFiltering).isFalse()
        assertThat(browser.sections().all { it.matchCount == null }).isTrue()
        assertThat(browser.rows.map { it.key }).containsNoDuplicates()
    }

    @Test
    fun `the last row of each section is marked so its corners round`() {
        val browser = build()
        val lastKeys = browser.tags().filter { it.isLast }.map { it.tag.key }
        assertThat(lastKeys).containsExactlyElementsIn(phone.directories.map { it.tags.last().key })
    }

    @Test
    fun `search matches names case-insensitively and highlights every hit`() {
        val browser = build(query = "EXPOSURE")
        val names = browser.tags().map { it.tag.name }
        assertThat(names).containsExactly("Exposure Time", "Exposure Program", "Exposure Bias Value", "Exposure Mode")
        val mode = browser.tags().single { it.tag.name == "Exposure Mode" }
        assertThat(mode.nameMatches).containsExactly(0 until 8)
        // "Auto exposure" matches in the value too.
        assertThat(mode.valueMatches).containsExactly(5 until 13)
        assertThat(browser.sections().single().matchCount).isEqualTo(4)
        assertThat(browser.matchingTags).isEqualTo(4)
    }

    @Test
    fun `search matches readable values`() {
        val tags = build(query = "pixel 8").tags()
        assertThat(tags.map { it.tag.name }).containsExactly("Model", "Lens Model")
    }

    @Test
    fun `a hit only in the raw value is explained in readable mode`() {
        val row = build(query = "2141/1000000").tags().single()
        assertThat(row.tag.name).isEqualTo("Exposure Time")
        assertThat(row.valueMatches).isEmpty()
        assertThat(row.otherMatch).isEqualTo(OtherValueMatch(ValueMode.Raw, "2141/1000000", listOf(0 until 12)))
    }

    @Test
    fun `raw mode shows raw values with tag ids and matches them directly`() {
        val browser = build(query = "886/100", mode = ValueMode.Raw)
        val row = browser.tags().single()
        assertThat(row.tag.name).isEqualTo("Shutter Speed Value")
        assertThat(row.value).isEqualTo("886/100")
        assertThat(row.valueMatches).containsExactly(0 until 7)
        assertThat(row.otherMatch).isNull()
        assertThat(row.showId).isTrue()
        assertThat(build(mode = ValueMode.Raw).tags().filter { it.tag.id != null }.all { it.showId }).isTrue()
    }

    @Test
    fun `search matches hex tag ids and reveals them in readable mode`() {
        val row = build(query = "0x829a").tags().single()
        assertThat(row.tag.name).isEqualTo("Exposure Time")
        assertThat(row.showId).isTrue()
        assertThat(row.idMatches).containsExactly(0 until 6)
        assertThat(build().tags().none { it.showId }).isTrue()
    }

    @Test
    fun `blank queries do not filter and unmatched queries leave nothing`() {
        assertThat(build(query = "   ").isFiltering).isFalse()
        val none = build(query = "no such tag anywhere")
        assertThat(none.isEmptyResult).isTrue()
        assertThat(none.rows).isEmpty()
        assertThat(none.matchingTags).isEqualTo(0)
    }

    @Test
    fun `collapsed sections keep their header but hide their rows`() {
        val browser = build(collapsed = setOf("exif-subifd", "gps"))
        val subIfd = browser.sections().single { it.sectionId == "exif-subifd" }
        assertThat(subIfd.expanded).isFalse()
        assertThat(browser.tags().none { it.tag.directoryId == "exif-subifd" || it.tag.directoryId == "gps" }).isTrue()
        assertThat(browser.tags().count { it.tag.directoryId == "exif-ifd0" }).isEqualTo(9)
        assertThat(browser.anyExpanded).isTrue()
        assertThat(build(collapsed = TagBrowserBuilder.allSections(phone)).anyExpanded).isFalse()
    }

    @Test
    fun `large MakerNotes and warnings start collapsed`() {
        val collapsed = TagBrowserBuilder.defaultCollapsed(raw)
        assertThat(collapsed).containsExactly("makernote-fujifilm", TagBrowserBuilder.WARNINGS_SECTION)
        val browser = build(raw, collapsed = collapsed)
        assertThat(browser.tags().none { it.tag.directoryId == "makernote-fujifilm" }).isTrue()
        val warnings = browser.sections().last()
        assertThat(warnings.sectionId).isEqualTo(TagBrowserBuilder.WARNINGS_SECTION)
        assertThat(warnings.group).isNull()
        assertThat(warnings.itemCount).isEqualTo(2)
        assertThat(browser.rows.filterIsInstance<WarningRow>()).isEmpty()
    }

    @Test
    fun `warnings are listed last when expanded and searched like tags`() {
        val expanded = build(raw)
        assertThat(expanded.rows.takeLast(2).map { it::class }).containsExactly(WarningRow::class, WarningRow::class)
        assertThat((expanded.rows.last() as WarningRow).isLast).isTrue()
        val searched = build(raw, query = "rdf:Description")
        assertThat(searched.rows.filterIsInstance<WarningRow>().map { it.index }).containsExactly(1)
    }

    @Test
    fun `a sensitivity filter shows only that category and hides warnings`() {
        val browser = build(raw, filter = SensitivityCategory.SerialNumber)
        assertThat(browser.tags().map { it.tag.name }).containsExactly("Body Serial Number", "Lens Serial Number", "Serial Number")
        assertThat(browser.sections().map { it.sectionId }).containsExactly("exif-subifd", "makernote-fujifilm").inOrder()
        assertThat(browser.rows.filterIsInstance<WarningRow>()).isEmpty()
        assertThat(browser.tags().all { it.sensitivity == SensitivityCategory.SerialNumber }).isTrue()
        assertThat(browser.matchingTags).isEqualTo(3)
    }

    @Test
    fun `filter and search combine`() {
        val browser = build(raw, query = "lens", filter = SensitivityCategory.SerialNumber)
        assertThat(browser.tags().map { it.tag.name }).containsExactly("Lens Serial Number")
    }

    @Test
    fun `a redacted GPS block is not counted as location data`() {
        val redacted = FakeReports.phoneJpegRedacted
        assertThat(redacted.sensitiveFindings.map { it.category }).contains(SensitivityCategory.Location)
        assertThat(TagBrowserBuilder.visibleFindings(redacted)).isEmpty()
        val browser = build(redacted)
        assertThat(browser.tags().filter { it.tag.directoryId == "gps" }.all { it.sensitivity == null }).isTrue()
        assertThat(TagBrowserBuilder.visibleFindings(phone)).isEqualTo(phone.sensitiveFindings)
    }

    @Test
    fun `hundreds of tags build quickly`() {
        val large = FakeReports.large(directoryCount = 20, tagsPerDirectory = 60)
        assertThat(large.tagCount).isEqualTo(1200)
        build(large, query = "value 1") // Warm up.
        val started = System.nanoTime()
        repeat(20) { build(large, query = "value 1${it % 10}") }
        val perBuildMillis = (System.nanoTime() - started) / 20 / 1_000_000.0
        assertThat(perBuildMillis).isLessThan(50.0)
        assertThat(build(large).tags()).hasSize(1200)
    }

    @Test
    fun `findMatches returns every non-overlapping hit`() {
        assertThat(TagBrowserBuilder.findMatches("aaaa", "aa")).containsExactly(0 until 2, 2 until 4).inOrder()
        assertThat(TagBrowserBuilder.findMatches("GPS Latitude", "lat")).containsExactly(4 until 7)
        assertThat(TagBrowserBuilder.findMatches("abc", "")).isEmpty()
        assertThat(TagBrowserBuilder.findMatches("ab", "abc")).isEmpty()
    }

    @Test
    fun `copy all is tab separated with one line per tag`() {
        val text = ReportText.tabSeparated(phone, "Group\tTag\tID\tValue\tRaw value")
        val lines = text.lines()
        assertThat(lines).hasSize(phone.tagCount + 1)
        assertThat(lines[0]).isEqualTo("Group\tTag\tID\tValue\tRaw value")
        assertThat(lines).contains("Exif SubIFD\tExposure Time\t0x829A\t0.00214 sec\t2141/1000000")
        assertThat(lines.drop(1).all { it.split('\t').size == 5 }).isTrue()
    }
}
