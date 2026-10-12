package io.github.fishpimp.exiflab.data.export.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import io.github.fishpimp.exiflab.data.export.ExportProgress
import io.github.fishpimp.exiflab.data.export.ExportResult
import io.github.fishpimp.exiflab.data.export.ExportSettings
import io.github.fishpimp.exiflab.data.export.ExportStage
import io.github.fishpimp.exiflab.data.export.MetadataExporter
import io.github.fishpimp.exiflab.data.export.PdfExport
import io.github.fishpimp.exiflab.data.export.ReportReader
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.metadata.model.GeoLocation
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.OutputStream
import java.time.LocalDate
import kotlin.math.roundToInt

/** Map images for the report; see [io.github.fishpimp.exiflab.ui.map.MapSnapshots]. */
interface PdfMaps {
    /** Whether maps can be made now: [PdfMapMode.Map], [PdfMapMode.Offline] or [PdfMapMode.Failed]. */
    suspend fun mode(): PdfMapMode

    /** A map of [location] for a [width] by [height] point box, or null when it cannot be made. */
    suspend fun render(location: GeoLocation, width: Int, height: Int, palette: PdfPalette): Bitmap?
}

/** A downscaled, upright preview of a photo, at most [maxPixels] on its long side. */
typealias PdfThumbnails = suspend (ref: PhotoRef, maxPixels: Int) -> Bitmap?

/**
 * Writes the PDF report with Android's [PdfDocument]: A4 pages in points, the app's fonts and
 * colors. One photo gives its section alone; several get a summary page first, then a section
 * each, every section starting on a new page. Every page has a footer with the credit line and
 * "Page X of Y".
 *
 * Photos are never all held at once. Several photos are read twice: once to lay out their
 * sections and count pages (cheap, no images), so the summary can name each photo's first page
 * and footers can give the total; then again to draw them with their thumbnail and map.
 */
class PdfReportWriter(
    private val context: Context,
    private val readReport: ReportReader,
    private val thumbnails: PdfThumbnails,
    private val maps: PdfMaps,
    private val today: () -> LocalDate = { LocalDate.now() },
) : PdfExport {

    override suspend fun write(
        photos: List<PhotoRef>,
        settings: ExportSettings,
        palette: PdfPalette,
        output: OutputStream,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult {
        val painter = PdfPainter(PdfFonts.load(context), palette)
        val job = Job(
            settings = settings,
            palette = palette,
            painter = painter,
            layout = PdfReportLayout(painter),
            builder = PdfSectionBuilder(context, settings),
            exportedOn = today(),
            mapMode = if (settings.includeMap) runCatching { maps.mode() }.getOrDefault(PdfMapMode.Failed) else PdfMapMode.Off,
        )
        val document = PdfDocument()
        try {
            val result = if (photos.size == 1) job.single(document, photos.single(), onProgress) else job.several(document, photos, onProgress)
            if (result.exported > 0) document.writeTo(output)
            return result
        } finally {
            document.close()
        }
    }

    private inner class Job(
        val settings: ExportSettings,
        val palette: PdfPalette,
        val painter: PdfPainter,
        val layout: PdfReportLayout,
        val builder: PdfSectionBuilder,
        val exportedOn: LocalDate,
        val mapMode: PdfMapMode,
    ) {
        private val credit = builder.footerCredit(exportedOn)
        private var pageNumber = 0

        suspend fun single(document: PdfDocument, ref: PhotoRef, onProgress: (ExportProgress) -> Unit): ExportResult {
            onProgress(ExportProgress(0, 1))
            val report = MetadataExporter.readOrNull(readReport, ref)
                ?: return ExportResult(0, listOf(MetadataExporter.displayName(ref, null)))
            val drawn = drawPhoto(document, ref, report, totalPages = null, thumbnailPixels = SINGLE_THUMBNAIL_PIXELS)
            onProgress(ExportProgress(1, 1))
            return ExportResult(if (drawn) 1 else 0, emptyList())
        }

        suspend fun several(document: PdfDocument, photos: List<PhotoRef>, onProgress: (ExportProgress) -> Unit): ExportResult {
            // Pass 1: lay out every section without images to count its pages.
            val readable = mutableListOf<Pair<PhotoRef, PdfSummaryEntry>>()
            val failed = mutableListOf<String>()
            onProgress(ExportProgress(0, photos.size, ExportStage.Preparing, 0f))
            photos.forEachIndexed { index, ref ->
                currentCoroutineContext().ensureActive()
                val report = MetadataExporter.readOrNull(readReport, ref)
                val name = MetadataExporter.displayName(ref, report)
                if (report == null) {
                    failed += name
                } else {
                    val pages = layout.layoutPhoto(builder.section(name, report, mapMode)).size
                    readable += ref to builder.summaryEntry(name, report, pages)
                }
                onProgress(ExportProgress(index + 1, photos.size, ExportStage.Preparing, PREPARE_SHARE * (index + 1) / photos.size))
            }
            if (readable.isEmpty()) return ExportResult(0, failed)

            // The summary, numbered once its own length is known.
            val entries = readable.map { it.second }
            val summaryLength = layout.layoutSummary(builder.summary(entries, null, failed, exportedOn)).size
            val firstPages = entries.runningFold(summaryLength + 1) { start, entry -> start + entry.pages }.dropLast(1)
            val summary = layout.layoutSummary(builder.summary(entries, firstPages, failed, exportedOn))
            val totalPages = summary.size + entries.sumOf { it.pages }
            summary.forEach { drawPage(document, it.ops, totalPages) { null } }

            // Pass 2: read each photo again and draw it with its images.
            var exported = 0
            val lost = mutableListOf<String>()
            readable.forEachIndexed { index, (ref, entry) ->
                currentCoroutineContext().ensureActive()
                val report = MetadataExporter.readOrNull(readReport, ref)
                if (report != null && drawPhoto(document, ref, report, totalPages, MULTI_THUMBNAIL_PIXELS, name = entry.name)) {
                    exported++
                } else {
                    lost += entry.name
                }
                val fraction = PREPARE_SHARE + (1f - PREPARE_SHARE) * (index + 1) / readable.size
                onProgress(ExportProgress(index + 1, readable.size, ExportStage.Writing, fraction))
            }
            return ExportResult(exported, failed + lost)
        }

        /** Draws one photo's section; false when nothing could be drawn. [totalPages] null means "this section's own length". */
        private suspend fun drawPhoto(
            document: PdfDocument,
            ref: PhotoRef,
            report: MetadataReport,
            totalPages: Int?,
            thumbnailPixels: Int,
            name: String = MetadataExporter.displayName(ref, report),
        ): Boolean {
            val location = report.location
            val wantsMap = mapMode == PdfMapMode.Map && location != null && !settings.redactSensitive
            val map = if (wantsMap) renderMap(location) else null
            val sectionMapMode = if (wantsMap && map == null) PdfMapMode.Failed else mapMode
            val pages = layout.layoutPhoto(builder.section(name, report, sectionMapMode))
            val thumbnail = try {
                thumbnails(ref, thumbnailPixels)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val total = totalPages ?: pages.size
            pages.forEach { page ->
                drawPage(document, page.ops, total) { image ->
                    when (image) {
                        PdfImage.Thumbnail -> thumbnail
                        PdfImage.Map -> map
                    }
                }
            }
            return pages.isNotEmpty()
        }

        private suspend fun renderMap(location: GeoLocation): Bitmap? = try {
            maps.render(location, layout.page.contentWidth.roundToInt(), PdfReportLayout.MAP_HEIGHT.roundToInt(), palette)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        private fun drawPage(document: PdfDocument, ops: List<PdfOp>, totalPages: Int, images: (PdfImage) -> Bitmap?) {
            pageNumber++
            val spec = layout.page
            val info = PdfDocument.PageInfo.Builder(spec.width.roundToInt(), spec.height.roundToInt(), pageNumber).create()
            val page = document.startPage(info)
            val footer = layout.footer(credit, builder.pageLabel(pageNumber, maxOf(totalPages, pageNumber)))
            painter.draw(page.canvas, ops + footer, images)
            document.finishPage(page)
        }
    }

    companion object {
        /** Thumbnail size for a one-photo report: 180 points at 288 dpi. */
        const val SINGLE_THUMBNAIL_PIXELS = 720

        /** Smaller for many photos, to keep the file size down. */
        const val MULTI_THUMBNAIL_PIXELS = 480

        /** Share of the progress bar for the first pass of a multi-photo report. */
        private const val PREPARE_SHARE = 0.15f
    }
}
