package io.github.fishpimp.exiflab.metadata.export

import io.github.fishpimp.exiflab.metadata.model.MetadataReport

/**
 * Writes metadata as CSV (RFC 4180): one row per tag, CRLF line ends, fields quoted when they hold
 * a comma, a quote, a line break or leading/trailing spaces. Several reports simply add their rows,
 * so one file can hold a whole selection. Streams: nothing is kept between [writeReport] calls.
 *
 * Columns, in this order ([COLUMNS]):
 * `file` (file name), `format` (container, e.g. "JPEG"), `directory` (e.g. "Exif SubIFD"),
 * `group` ([io.github.fishpimp.exiflab.metadata.model.DirectoryGroup] name, e.g. "Exif"),
 * `tag_id` (hex id such as "0x9003", empty for XMP properties), `tag` (name), `value` (readable),
 * `raw_value` (as stored; empty unless [ExportOptions.includeRawValues]) and `sensitive` (privacy
 * category such as "Location", empty for ordinary tags).
 *
 * @param spreadsheetSafe for spreadsheet apps: starts the file with a UTF-8 byte order mark so
 *   they detect the encoding, and prefixes values that a spreadsheet would run as a formula
 *   ("=", "+", "-", "@" followed by more than a number) with an apostrophe. NUL characters, which
 *   many tools stop reading at, are always dropped.
 */
class MetadataCsvWriter(
    private val out: Appendable,
    private val options: ExportOptions = ExportOptions(),
    private val spreadsheetSafe: Boolean = true,
) {
    private var headerWritten = false

    /** Writes the byte order mark (when [spreadsheetSafe]) and the header row. Called by the first [writeReport] if needed. */
    fun writeHeader() {
        if (headerWritten) return
        headerWritten = true
        if (spreadsheetSafe) out.append(BYTE_ORDER_MARK)
        writeRow(COLUMNS)
    }

    /** Writes one row per tag of [report], under [fileName] (the report's own name by default). */
    fun writeReport(report: MetadataReport, fileName: String? = report.fileName) {
        writeHeader()
        val exported = report.forExport(options)
        val name = fileName.orEmpty()
        val format = exported.format.displayName
        for (directory in exported.directories) {
            for (tag in directory.tags) {
                writeRow(
                    listOf(
                        name,
                        format,
                        directory.name,
                        directory.group.name,
                        tag.hexId.orEmpty(),
                        tag.name,
                        tag.displayValue,
                        if (options.includeRawValues) tag.rawValue else "",
                        tag.sensitivity?.name.orEmpty(),
                    ),
                )
            }
        }
    }

    private fun writeRow(fields: List<String>) {
        fields.forEachIndexed { index, field ->
            if (index > 0) out.append(',')
            out.append(escape(field, spreadsheetSafe))
        }
        out.append(LINE_END)
    }

    companion object {
        val COLUMNS: List<String> = listOf("file", "format", "directory", "group", "tag_id", "tag", "value", "raw_value", "sensitive")

        const val MIME_TYPE = "text/csv"
        const val BYTE_ORDER_MARK = '﻿'
        private const val LINE_END = "\r\n"

        /** Matches values a spreadsheet reads as a plain number or fraction, which are safe to leave alone. */
        private val plainNumber = Regex("""[-+]?[0-9]+([.,][0-9]+)?(/[0-9]+)?([eE][-+]?[0-9]+)?""")

        /** One field, quoted only when RFC 4180 requires it (or for surrounding spaces, which some readers trim). */
        fun escape(field: String, spreadsheetSafe: Boolean = false): String {
            var value = if (field.indexOf('\u0000') >= 0) field.replace("\u0000", "") else field
            if (spreadsheetSafe && looksLikeFormula(value)) value = "'$value"
            val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
                (value.isNotEmpty() && (value.first() == ' ' || value.last() == ' '))
            return if (needsQuotes) "\"" + value.replace("\"", "\"\"") + "\"" else value
        }

        private fun looksLikeFormula(value: String): Boolean {
            if (value.isEmpty()) return false
            return when (value.first()) {
                '=', '@', '\t', '\r' -> true
                '+', '-' -> value.length > 1 && !plainNumber.matches(value)
                else -> false
            }
        }
    }
}
