package io.github.fishpimp.exiflab.ui.photo

import androidx.compose.runtime.Immutable
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.LocationStatus
import io.github.fishpimp.exiflab.metadata.model.MetadataDirectory
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.MetadataTag
import io.github.fishpimp.exiflab.metadata.model.SensitiveFinding
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory

/** Which form of a tag value the browser shows. */
enum class ValueMode {
    /** Decoded values, e.g. "1/250 sec" or "f/1.8". */
    Readable,

    /** Values as stored, e.g. "1/250" or "18/10", in monospace with the hex tag id. */
    Raw,
}

/** One row of the tag browser's flat list. [key] is stable and unique, for lazy list keys. */
@Immutable
sealed interface BrowserRow {
    val key: String
}

/**
 * The header of a collapsible section: a metadata directory, or the parse warnings.
 *
 * @property group null for the warnings section.
 * @property itemCount tags (or warnings) in the section.
 * @property matchCount items that pass the active search or filter; null when nothing filters.
 */
@Immutable
data class SectionHeaderRow(
    val sectionId: String,
    val name: String,
    val group: DirectoryGroup?,
    val itemCount: Int,
    val matchCount: Int?,
    val expanded: Boolean,
) : BrowserRow {
    override val key: String get() = "section:$sectionId"
}

/**
 * One tag. Match ranges index into [MetadataTag.name], [value] and [MetadataTag.hexId].
 *
 * @property value the shown value: [MetadataTag.displayValue] or [MetadataTag.rawValue] depending on the mode.
 * @property sensitivity the privacy category the row is marked with; see [TagBrowserBuilder.visibleFindings].
 * @property showId true in raw mode, or when the search matched the tag id.
 * @property otherMatch a search hit in the value form that is not shown, so the row can explain why it matched.
 * @property isLast the last row of its section, which rounds the section's bottom corners.
 */
@Immutable
data class TagRow(
    val tag: MetadataTag,
    val directoryName: String,
    val value: String,
    val nameMatches: List<IntRange>,
    val valueMatches: List<IntRange>,
    val idMatches: List<IntRange>,
    val sensitivity: SensitivityCategory?,
    val showId: Boolean,
    val otherMatch: OtherValueMatch?,
    val isLast: Boolean,
) : BrowserRow {
    override val key: String get() = "tag:${tag.key}"
}

/** A search hit in the hidden value form: the raw value in readable mode, or the reverse. */
@Immutable
data class OtherValueMatch(val mode: ValueMode, val text: String, val matches: List<IntRange>)

/** One parse warning inside the warnings section. */
@Immutable
data class WarningRow(val index: Int, val text: String, val matches: List<IntRange>, val isLast: Boolean) : BrowserRow {
    override val key: String get() = "warning:$index"
}

/**
 * The tag browser after search, filter and collapse were applied.
 *
 * @property totalTags tags in the whole report.
 * @property sectionCount directories in the whole report.
 * @property matchingTags tags that pass the search and filter (all tags when nothing filters).
 * @property isFiltering true when a search query or a sensitivity filter is active.
 * @property anyExpanded true when at least one visible section is expanded; decides what "expand/collapse all" does.
 */
@Immutable
data class TagBrowser(
    val rows: List<BrowserRow>,
    val totalTags: Int,
    val sectionCount: Int,
    val matchingTags: Int,
    val isFiltering: Boolean,
    val anyExpanded: Boolean,
) {
    /** Search or filter is active and nothing passes it. */
    val isEmptyResult: Boolean get() = isFiltering && rows.none { it is TagRow || it is WarningRow }
}

/** Builds the browser rows from a report. Pure and fast enough for thousands of tags; run it off the main thread. */
object TagBrowserBuilder {
    /** Section id of the parse warnings; directory ids never start with an underscore. */
    const val WARNINGS_SECTION = "__warnings"

    /** MakerNote directories with more tags than this start collapsed. */
    const val LARGE_SECTION = 30

    /** Sections collapsed when a report is first shown: large MakerNotes and the warnings. */
    fun defaultCollapsed(report: MetadataReport): Set<String> = buildSet {
        report.directories
            .filter { it.group == DirectoryGroup.MakerNote && it.tags.size > LARGE_SECTION }
            .forEach { add(it.id) }
        add(WARNINGS_SECTION)
    }

    /**
     * The findings worth a privacy badge. A redacted GPS block (zeroed or blank, as the photo
     * picker leaves it) identifies nothing, so its tags do not count as location data.
     */
    fun visibleFindings(report: MetadataReport): List<SensitiveFinding> {
        if (report.locationStatus != LocationStatus.Redacted) return report.sensitiveFindings
        val blankGps = report.directories.filter { it.group == DirectoryGroup.Gps }.flatMapTo(HashSet()) { directory ->
            directory.tags.map { it.key }
        }
        return report.sensitiveFindings.mapNotNull { finding ->
            if (finding.category != SensitivityCategory.Location) {
                finding
            } else {
                finding.tagKeys.filterNot { it in blankGps }.takeIf { it.isNotEmpty() }?.let { finding.copy(tagKeys = it) }
            }
        }
    }

    /** Every section id of [report], for "collapse all". */
    fun allSections(report: MetadataReport): Set<String> = buildSet {
        report.directories.forEach { add(it.id) }
        if (report.warnings.isNotEmpty()) add(WARNINGS_SECTION)
    }

    /**
     * @param query case-insensitive text matched against tag names, both value forms and hex ids.
     * @param filter only tags of this sensitivity category, when set.
     * @param collapsed ids of collapsed sections.
     */
    fun build(
        report: MetadataReport,
        query: String,
        mode: ValueMode,
        filter: SensitivityCategory?,
        collapsed: Set<String>,
    ): TagBrowser {
        val needle = query.trim()
        val isFiltering = needle.isNotEmpty() || filter != null
        val findings = visibleFindings(report)
        val sensitivity = HashMap<String, SensitivityCategory>()
        findings.forEach { finding -> finding.tagKeys.forEach { sensitivity[it] = finding.category } }
        val filterKeys = filter?.let { category -> findings.firstOrNull { it.category == category }?.tagKeys?.toHashSet() ?: emptySet() }
        val rows = ArrayList<BrowserRow>(report.tagCount + report.directories.size + 2)
        var matchingTags = 0
        var anyExpanded = false

        for (directory in report.directories) {
            val matches = directory.tags.mapNotNull { tag ->
                if (filterKeys != null && tag.key !in filterKeys) null else matchTag(tag, directory, needle, mode, sensitivity[tag.key])
            }
            if (isFiltering && matches.isEmpty()) continue
            matchingTags += matches.size
            val expanded = directory.id !in collapsed
            anyExpanded = anyExpanded || expanded
            rows += SectionHeaderRow(
                sectionId = directory.id,
                name = directory.name,
                group = directory.group,
                itemCount = directory.tags.size,
                matchCount = if (isFiltering) matches.size else null,
                expanded = expanded,
            )
            if (expanded) {
                matches.forEachIndexed { index, row -> rows += if (index == matches.lastIndex) row.copy(isLast = true) else row }
            }
        }

        // Warnings belong to the file, not to a sensitivity category, so a filter hides them.
        if (report.warnings.isNotEmpty() && filter == null) {
            val warnings = report.warnings.mapIndexedNotNull { index, text ->
                val hits = findMatches(text, needle)
                if (needle.isNotEmpty() && hits.isEmpty()) null else WarningRow(index, text, hits, isLast = false)
            }
            if (!isFiltering || warnings.isNotEmpty()) {
                val expanded = WARNINGS_SECTION !in collapsed
                anyExpanded = anyExpanded || expanded
                rows += SectionHeaderRow(
                    sectionId = WARNINGS_SECTION,
                    name = "",
                    group = null,
                    itemCount = report.warnings.size,
                    matchCount = if (isFiltering) warnings.size else null,
                    expanded = expanded,
                )
                if (expanded) {
                    warnings.forEachIndexed { index, row -> rows += if (index == warnings.lastIndex) row.copy(isLast = true) else row }
                }
            }
        }

        return TagBrowser(
            rows = rows,
            totalTags = report.tagCount,
            sectionCount = report.directories.size,
            matchingTags = if (isFiltering) matchingTags else report.tagCount,
            isFiltering = isFiltering,
            anyExpanded = anyExpanded,
        )
    }

    /** The row for [tag], or null when [needle] is set and matches none of its fields. */
    private fun matchTag(
        tag: MetadataTag,
        directory: MetadataDirectory,
        needle: String,
        mode: ValueMode,
        sensitivity: SensitivityCategory?,
    ): TagRow? {
        val shown = if (mode == ValueMode.Raw) tag.rawValue else tag.displayValue
        val other = if (mode == ValueMode.Raw) tag.displayValue else tag.rawValue
        val hexId = tag.hexId.orEmpty()
        val nameHits = findMatches(tag.name, needle)
        val valueHits = findMatches(shown, needle)
        val idHits = findMatches(hexId, needle)
        val otherHits = if (other != shown) findMatches(other, needle) else emptyList()
        if (needle.isNotEmpty() && nameHits.isEmpty() && valueHits.isEmpty() && idHits.isEmpty() && otherHits.isEmpty()) {
            return null
        }
        // Explain a hit that is only in the hidden value form; otherwise the row would look unrelated.
        val otherMatch = if (nameHits.isEmpty() && valueHits.isEmpty() && idHits.isEmpty() && otherHits.isNotEmpty()) {
            OtherValueMatch(if (mode == ValueMode.Raw) ValueMode.Readable else ValueMode.Raw, other, otherHits)
        } else {
            null
        }
        return TagRow(
            tag = tag,
            directoryName = directory.name,
            value = shown,
            nameMatches = nameHits,
            valueMatches = valueHits,
            idMatches = idHits,
            sensitivity = sensitivity,
            showId = tag.hexId != null && (mode == ValueMode.Raw || idHits.isNotEmpty()),
            otherMatch = otherMatch,
            isLast = false,
        )
    }

    /** Every non-overlapping case-insensitive occurrence of [needle] in [text]; empty when [needle] is blank. */
    fun findMatches(text: String, needle: String): List<IntRange> {
        if (needle.isEmpty() || text.length < needle.length) return emptyList()
        var start = text.indexOf(needle, ignoreCase = true)
        if (start < 0) return emptyList()
        val hits = ArrayList<IntRange>(2)
        while (start >= 0) {
            hits += start until start + needle.length
            start = text.indexOf(needle, start + needle.length, ignoreCase = true)
        }
        return hits
    }
}
