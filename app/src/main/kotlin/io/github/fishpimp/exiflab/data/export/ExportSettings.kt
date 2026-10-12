package io.github.fishpimp.exiflab.data.export

import io.github.fishpimp.exiflab.metadata.export.ExportOptions
import io.github.fishpimp.exiflab.metadata.export.MetadataCsvWriter
import io.github.fishpimp.exiflab.metadata.export.MetadataJsonWriter

/** The file types metadata can be exported as. */
enum class ExportFormat(val extension: String, val mimeType: String) {
    Csv("csv", MetadataCsvWriter.MIME_TYPE),
    Json("json", MetadataJsonWriter.MIME_TYPE),
    Pdf("pdf", "application/pdf"),
}

/**
 * What the user chose in the export sheet. Options that do not apply to [format] are kept, so
 * switching formats back and forth does not lose them, but [effective] turns them off.
 *
 * @property includeRawValues adds the value as stored next to the readable one.
 * @property redactSensitive leaves out location, names, serial numbers and other identifying values.
 * @property includeMap PDF only: a map of the location, loaded from the map tile server.
 * @property spreadsheetFriendly CSV only: a byte order mark and formula protection for spreadsheet apps.
 */
data class ExportSettings(
    val format: ExportFormat = ExportFormat.Pdf,
    val includeRawValues: Boolean = true,
    val redactSensitive: Boolean = false,
    val includeMap: Boolean = true,
    val spreadsheetFriendly: Boolean = true,
) {
    /** Whether the map switch means anything: a PDF that keeps the location. */
    val mapApplies: Boolean get() = format == ExportFormat.Pdf && !redactSensitive

    val spreadsheetApplies: Boolean get() = format == ExportFormat.Csv

    /** These settings with every option that does not apply switched off. */
    val effective: ExportSettings
        get() = copy(includeMap = includeMap && mapApplies, spreadsheetFriendly = spreadsheetFriendly && spreadsheetApplies)

    val coreOptions: ExportOptions get() = ExportOptions(includeRawValues = includeRawValues, redactSensitive = redactSensitive)

    companion object {
        /** A single photo reads best as a report; many photos fit a spreadsheet better. */
        fun defaultFor(photoCount: Int): ExportSettings =
            ExportSettings(format = if (photoCount > 1) ExportFormat.Csv else ExportFormat.Pdf)
    }
}

/** What an export is busy with. */
enum class ExportStage {
    /** A report with several photos reads them all once to number its pages. */
    Preparing,

    /** Writing photos into the file. */
    Writing,
}

/**
 * How far an export has come: [done] of [total] photos in [stage], and [fraction] of the whole
 * job (a PDF report spends a small part of it preparing).
 */
data class ExportProgress(
    val done: Int,
    val total: Int,
    val stage: ExportStage = ExportStage.Writing,
    val fraction: Float = if (total <= 0) 0f else done.toFloat() / total,
)

/**
 * What an export wrote.
 *
 * @property exported photos in the file.
 * @property failed names of the photos that could not be read and were left out.
 */
data class ExportResult(val exported: Int, val failed: List<String>)
