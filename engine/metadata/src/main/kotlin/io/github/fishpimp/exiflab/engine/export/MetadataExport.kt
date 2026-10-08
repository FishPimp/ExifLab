package io.github.fishpimp.exiflab.engine.export

import io.github.fishpimp.exiflab.model.MetadataDocument

public data class ExportEntry(val fileName: String, val document: MetadataDocument)

/** CSV and JSON exports (the PDF report is rendered by the app). */
public object MetadataExport {
    /** RFC 4180 CSV, one row per tag: file, group, tag, tag id, raw value, display value, privacy. UTF-8 with BOM. */
    public fun toCsv(entries: List<ExportEntry>): String = TODO("MetadataExport.toCsv")

    /** Pretty JSON: [{ file, format, size, summary, groups: [{ name, category, tags: [{ name, id, raw, display, privacy }] }] }]. */
    public fun toJson(entries: List<ExportEntry>): String = TODO("MetadataExport.toJson")
}
