package io.github.fishpimp.exiflab.data.export

import io.github.fishpimp.exiflab.data.export.pdf.PdfPalette
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.metadata.export.ExportInfo
import io.github.fishpimp.exiflab.metadata.export.MetadataCsvWriter
import io.github.fishpimp.exiflab.metadata.export.MetadataJsonWriter
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.OffsetDateTime

/** Reads one photo's metadata; blocking work belongs on an IO dispatcher inside. */
typealias ReportReader = suspend (PhotoRef) -> MetadataReport

/** Writes a PDF report of [photos] to [output] in [palette]'s colors; see [io.github.fishpimp.exiflab.data.export.pdf.PdfReportWriter]. */
fun interface PdfExport {
    suspend fun write(
        photos: List<PhotoRef>,
        settings: ExportSettings,
        palette: PdfPalette,
        output: OutputStream,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult
}

/**
 * Exports the metadata of one or more photos as CSV, JSON or a PDF report. Photos are read one
 * at a time and written straight out, so memory use does not grow with the selection. A photo
 * that cannot be read is left out and named in the result; cancelling stops between photos.
 *
 * @param appVersion goes into the JSON header.
 * @param now the export time for the JSON header.
 */
class MetadataExporter(
    private val readReport: ReportReader,
    private val pdf: PdfExport,
    private val appVersion: String,
    private val now: () -> OffsetDateTime = { OffsetDateTime.now() },
) {
    /** Writes [photos] to [output] as [settings] say; [palette] colors a PDF report and is required for one. */
    suspend fun export(
        photos: List<PhotoRef>,
        settings: ExportSettings,
        output: OutputStream,
        palette: PdfPalette? = null,
        onProgress: (ExportProgress) -> Unit = {},
    ): ExportResult {
        val effective = settings.effective
        return when (effective.format) {
            ExportFormat.Pdf -> pdf.write(photos, effective, requireNotNull(palette) { "A PDF report needs a palette" }, output, onProgress)
            ExportFormat.Csv, ExportFormat.Json -> writeText(photos, effective, output, onProgress)
        }
    }

    private suspend fun writeText(
        photos: List<PhotoRef>,
        settings: ExportSettings,
        output: OutputStream,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult {
        val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8), BUFFER_SIZE)
        val failed = mutableListOf<String>()
        var exported = 0
        val csv = if (settings.format == ExportFormat.Csv) MetadataCsvWriter(writer, settings.coreOptions, settings.spreadsheetFriendly) else null
        val json = if (settings.format == ExportFormat.Json) MetadataJsonWriter(writer, ExportInfo(appVersion, now()), settings.coreOptions) else null
        csv?.writeHeader()
        json?.begin()
        onProgress(ExportProgress(0, photos.size))
        photos.forEachIndexed { index, ref ->
            currentCoroutineContext().ensureActive()
            val report = readOrNull(readReport, ref)
            val name = displayName(ref, report)
            if (report == null) {
                failed += name
            } else {
                csv?.writeReport(report, name)
                json?.writeReport(report, name)
                exported++
            }
            onProgress(ExportProgress(index + 1, photos.size))
        }
        json?.end()
        writer.flush()
        return ExportResult(exported, failed)
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024

        /** The name a photo goes by in exports: what the provider calls it, else what the file says. */
        fun displayName(ref: PhotoRef, report: MetadataReport?): String =
            ref.displayName ?: report?.fileName ?: ref.uri.substringAfterLast('/')

        /** The photo's report, or null when it cannot be read. Cancellation still propagates. */
        suspend fun readOrNull(readReport: ReportReader, ref: PhotoRef): MetadataReport? = try {
            readReport(ref)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
