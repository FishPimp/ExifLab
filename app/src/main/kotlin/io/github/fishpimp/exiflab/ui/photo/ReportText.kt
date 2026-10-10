package io.github.fishpimp.exiflab.ui.photo

import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.MetadataTag

/** Plain-text renderings of report content for the clipboard. */
object ReportText {
    /**
     * Every tag of every directory, one per line, tab-separated so it pastes into a spreadsheet:
     * group, tag name, tag id, readable value, raw value. [header] is the localized first line
     * (column titles separated by tabs). Tabs and line breaks inside values become spaces.
     */
    fun tabSeparated(report: MetadataReport, header: String): String = buildString {
        append(header)
        for (directory in report.directories) {
            for (tag in directory.tags) {
                append('\n')
                append(cell(directory.name)).append('\t')
                append(cell(tag.name)).append('\t')
                append(tag.hexId.orEmpty()).append('\t')
                append(cell(tag.displayValue)).append('\t')
                append(cell(tag.rawValue))
            }
        }
    }

    /** "Exposure Time: 1/250 sec", for "Copy name and value". */
    fun nameAndValue(tag: MetadataTag, value: String): String = "${tag.name}: $value"

    private fun cell(text: String): String =
        if (text.none { it == '\t' || it == '\n' || it == '\r' }) text else text.replace(Regex("[\t\r\n]+"), " ")
}
