package io.github.fishpimp.exiflab.data.export.pdf

import android.content.Context
import android.text.format.Formatter
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.export.ExportSettings
import io.github.fishpimp.exiflab.metadata.export.cameraLabel
import io.github.fishpimp.exiflab.metadata.model.LocationStatus
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.ui.format.CaptureTimeFormat
import io.github.fishpimp.exiflab.ui.format.ExposureFormat
import io.github.fishpimp.exiflab.ui.format.LocationFormat
import io.github.fishpimp.exiflab.ui.photo.labelRes
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** What the location block shows in place of a map. */
enum class PdfMapMode {
    /** A MapLibre snapshot. */
    Map,

    /** The user turned the map off. */
    Off,

    /** Offline mode is on or there is no connection. */
    Offline,

    /** Maps cannot be drawn on this device, or the snapshot failed. */
    Failed,
}

/** One line of the summary page. */
data class PdfSummaryEntry(
    val name: String,
    val camera: String?,
    val taken: String?,
    /** Privacy category labels found, empty when none. */
    val privacy: List<String>,
    val hasLocation: Boolean,
    /** Pages this photo's section takes. */
    val pages: Int,
)

/**
 * Turns a metadata report into the localized, formatted content of its report section, using the
 * viewer's own wording and number formats.
 */
class PdfSectionBuilder(
    private val context: Context,
    private val settings: ExportSettings,
    private val locale: Locale = context.resources.configuration.locales[0] ?: Locale.getDefault(),
) {
    private val resources = context.resources

    fun section(name: String, report: MetadataReport, mapMode: PdfMapMode): PdfPhotoSection {
        val summary = report.summary
        val redact = settings.redactSensitive
        return PdfPhotoSection(
            overline = string(R.string.export_pdf_overline),
            title = name,
            subtitle = listOfNotNull(report.format.displayName, report.fileSize?.let(::fileSize)).joinToString(" · ").ifEmpty { null },
            thumbnailAspect = summary.width?.let { width ->
                summary.height?.takeIf { width > 0 && it > 0 }?.let { height ->
                    val (shownWidth, shownHeight) = ExposureFormat.orientedSize(width, height, summary.orientation)
                    shownWidth.toFloat() / shownHeight
                }
            },
            camera = camera(report) ?: string(R.string.photo_no_camera_title),
            lens = summary.lens,
            exposure = exposure(report),
            facts = facts(report),
            privacy = privacy(report, redact),
            location = location(report, redact, mapMode),
            tagsTitle = string(R.string.export_pdf_tags_title),
            tables = report.directories.filter { it.tags.isNotEmpty() }.map { directory ->
                PdfTable(
                    title = directory.name,
                    detail = resources.getQuantityString(R.plurals.photo_tag_count, directory.tags.size, directory.tags.size),
                    continued = string(R.string.export_pdf_continued),
                    columns = columns(),
                    rows = directory.tags.map { tag ->
                        val hidden = redact && tag.sensitivity != null
                        val name = tag.sensitivity?.let { tag.name + "\n" + resources.getString(R.string.export_pdf_sensitive_tag, string(it.labelRes)) } ?: tag.name
                        val value = if (hidden) string(R.string.export_pdf_redacted_value) else tag.displayValue
                        val raw = if (hidden) string(R.string.export_pdf_redacted_value) else tag.rawValue
                        if (settings.includeRawValues) listOf(name, tag.hexId.orEmpty(), value, raw) else listOf(name, tag.hexId.orEmpty(), value)
                    },
                )
            },
            warnings = report.warnings.takeIf { it.isNotEmpty() }?.let { PdfNotes(string(R.string.photo_warnings_title), it) },
        )
    }

    /** The summary page's line for a photo whose section takes [pages] pages. */
    fun summaryEntry(name: String, report: MetadataReport, pages: Int): PdfSummaryEntry = PdfSummaryEntry(
        name = name,
        camera = camera(report),
        taken = report.summary.capturedAt?.let { taken(report) },
        privacy = report.sensitiveFindings.filter { it.tagKeys.isNotEmpty() }.map { string(it.category.labelRes) },
        hasLocation = report.locationStatus == LocationStatus.Present,
        pages = pages,
    )

    /**
     * The first page of a multi-photo report. [firstPages] holds each entry's first page number
     * (1-based), or is null while the page count is still being worked out.
     */
    fun summary(entries: List<PdfSummaryEntry>, firstPages: List<Int>?, notIncluded: List<String>, exportedOn: LocalDate): PdfSummary {
        val total = entries.size + notIncluded.size
        val subtitle = buildList {
            add(resources.getString(R.string.export_pdf_summary_subtitle, date(exportedOn)))
            if (settings.redactSensitive) add(string(R.string.export_pdf_summary_redacted))
        }.joinToString(" · ")
        val stats = buildList {
            add(PdfStat(entries.size.toString(), resources.getQuantityString(R.plurals.export_pdf_summary_photos, entries.size)))
            add(PdfStat(entries.count { it.privacy.isNotEmpty() }.toString(), string(R.string.export_pdf_summary_private)))
            add(PdfStat(entries.count { it.hasLocation }.toString(), string(R.string.export_pdf_summary_located)))
            if (notIncluded.isNotEmpty()) add(PdfStat(notIncluded.size.toString(), string(R.string.export_pdf_summary_unreadable)))
        }
        val none = string(R.string.export_pdf_privacy_none)
        val table = PdfTable(
            title = "",
            detail = "",
            continued = string(R.string.export_pdf_continued),
            columns = listOf(
                PdfColumn("#", 0.35f, PdfStyles.CellMuted),
                PdfColumn(string(R.string.export_pdf_column_file), 1.9f),
                PdfColumn(string(R.string.export_pdf_column_camera), 1.4f),
                PdfColumn(string(R.string.photo_captured), 1.5f),
                PdfColumn(string(R.string.photo_privacy_title), 1.4f),
                PdfColumn(string(R.string.export_pdf_column_page), 0.5f, PdfStyles.CellMono),
            ),
            rows = entries.mapIndexed { index, entry ->
                listOf(
                    (index + 1).toString(),
                    entry.name,
                    entry.camera.orEmpty(),
                    entry.taken.orEmpty(),
                    entry.privacy.joinToString(", ").ifEmpty { none },
                    firstPages?.getOrNull(index)?.toString() ?: "000",
                )
            },
        )
        return PdfSummary(
            overline = string(R.string.export_pdf_overline),
            title = resources.getQuantityString(R.plurals.photo_count, total, total),
            subtitle = subtitle,
            stats = stats,
            table = table,
            notIncluded = notIncluded.takeIf { it.isNotEmpty() }?.let { PdfNotes(string(R.string.export_pdf_not_included), it) },
        )
    }

    /** "Created with ExifLab - 12 Oct 2026". */
    fun footerCredit(exportedOn: LocalDate): String = resources.getString(R.string.export_pdf_footer, date(exportedOn))

    fun pageLabel(number: Int, total: Int): String = resources.getString(R.string.export_pdf_page, number, total)

    private fun columns(): List<PdfColumn> {
        val tag = PdfColumn(string(R.string.export_pdf_column_tag), 1.3f)
        val id = PdfColumn(string(R.string.export_pdf_column_id), 0.5f, PdfStyles.CellMuted)
        return if (settings.includeRawValues) {
            listOf(tag, id, PdfColumn(string(R.string.export_pdf_column_value), 2.0f), PdfColumn(string(R.string.export_pdf_column_raw), 1.5f, PdfStyles.CellMono))
        } else {
            listOf(tag, id, PdfColumn(string(R.string.export_pdf_column_value), 3.5f))
        }
    }

    private fun camera(report: MetadataReport): String? = cameraLabel(report.summary.make, report.summary.model)

    private fun exposure(report: MetadataReport): List<PdfStat> {
        val summary = report.summary
        return listOfNotNull(
            summary.fNumber?.let { ExposureFormat.fNumber(it, locale) }?.let {
                PdfStat(resources.getString(R.string.photo_value_aperture, it), string(R.string.photo_spec_aperture))
            },
            summary.exposureTime?.let { ExposureFormat.shutterSpeedText(it, locale) }?.let {
                PdfStat(resources.getString(R.string.photo_value_shutter, it), string(R.string.photo_spec_shutter))
            },
            summary.iso?.takeIf { it > 0 }?.let {
                PdfStat(resources.getString(R.string.photo_value_iso, it.toString()), string(R.string.photo_spec_iso))
            },
            summary.focalLengthMm?.let { ExposureFormat.focalLength(it, locale) }?.let {
                PdfStat(resources.getString(R.string.photo_value_focal_length, it), string(R.string.photo_spec_focal_length))
            },
        )
    }

    private fun facts(report: MetadataReport): List<PdfFact> {
        val summary = report.summary
        val resolution = summary.width?.let { width ->
            summary.height?.takeIf { width > 0 && it > 0 }?.let { height ->
                val (shownWidth, shownHeight) = ExposureFormat.orientedSize(width, height, summary.orientation)
                val dimensions = resources.getString(R.string.photo_value_dimensions, shownWidth, shownHeight)
                ExposureFormat.megapixels(width, height, locale)
                    ?.let { "$dimensions (${resources.getString(R.string.photo_value_megapixels, it)})" } ?: dimensions
            }
        }
        return listOfNotNull(
            summary.capturedAt?.let { PdfFact(string(R.string.photo_captured), taken(report)) },
            resolution?.let { PdfFact(string(R.string.photo_spec_resolution), it) },
            summary.exposureBiasEv?.let { ExposureFormat.exposureBias(it, locale) }?.let {
                PdfFact(string(R.string.photo_spec_exposure_bias), resources.getString(R.string.photo_value_exposure_bias, it))
            },
            report.fileSize?.let { PdfFact(string(R.string.export_pdf_size), fileSize(it)) },
            summary.colorProfile?.let { PdfFact(string(R.string.export_pdf_color_profile), it) },
            summary.software?.let { PdfFact(string(R.string.export_pdf_software), it) },
        )
    }

    /** "Jun 14, 2026, 7:42:07 PM" over "UTC+02:00" (or "Time zone unknown"). */
    private fun taken(report: MetadataReport): String {
        val summary = report.summary
        val local = summary.capturedAt ?: return ""
        val offset = summary.utcOffset
        val zone = if (offset == null) {
            string(R.string.photo_time_zone_unknown)
        } else {
            resources.getString(R.string.photo_value_utc_offset, CaptureTimeFormat.utcOffset(offset))
        }
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).format(local) + "\n" + zone
    }

    private fun privacy(report: MetadataReport, redact: Boolean): PdfPrivacy {
        val findings = report.sensitiveFindings.filter { it.tagKeys.isNotEmpty() }
        if (findings.isEmpty()) return PdfPrivacy(string(R.string.photo_privacy_title), string(R.string.photo_privacy_clean), emptyList())
        val names = report.directories.flatMap { it.tags }.associate { it.key to it.name }
        val intro = string(R.string.export_pdf_privacy_found) + if (redact) " " + string(R.string.export_pdf_privacy_redacted) else ""
        return PdfPrivacy(
            title = string(R.string.photo_privacy_title),
            intro = intro,
            findings = findings.map { finding ->
                val tagNames = finding.tagKeys.mapNotNull { names[it] }.distinct()
                val shown = tagNames.take(MAX_FINDING_TAGS).joinToString(", ")
                val more = tagNames.size - MAX_FINDING_TAGS
                val list = if (more > 0) "$shown " + resources.getQuantityString(R.plurals.export_pdf_more_tags, more, more) else shown
                val count = resources.getQuantityString(R.plurals.photo_tag_count, finding.tagKeys.size, finding.tagKeys.size)
                PdfFinding(string(finding.category.labelRes), if (list.isEmpty()) count else "$count: $list")
            },
        )
    }

    private fun location(report: MetadataReport, redact: Boolean, mapMode: PdfMapMode): PdfLocationBlock {
        val title = string(R.string.photo_location_title)
        val location = report.location
        if (redact && (location != null || report.locationStatus == LocationStatus.Present)) {
            return PdfLocationBlock(title, null, null, emptyList(), PdfMap.None, string(R.string.export_pdf_location_left_out))
        }
        if (location == null) {
            val message = if (report.locationStatus == LocationStatus.Redacted) R.string.photo_location_redacted else R.string.photo_location_absent
            return PdfLocationBlock(title, null, null, emptyList(), PdfMap.None, string(message))
        }
        val coordinates = LocationFormat.decimalDegrees(location.latitude, location.longitude)
        val facts = listOfNotNull(
            // Always listed, map or not, so the block is the same size either way.
            PdfFact(string(R.string.map_coordinates), coordinates, mono = true),
            location.altitudeMeters?.let { meters ->
                val value = LocationFormat.altitudeMeters(meters)
                PdfFact(
                    string(R.string.photo_location_altitude),
                    resources.getString(if (meters < 0) R.string.photo_value_altitude_below else R.string.photo_value_altitude, value),
                )
            },
            location.directionDegrees?.let { degrees ->
                PdfFact(
                    string(R.string.photo_location_direction),
                    resources.getString(R.string.photo_value_direction, LocationFormat.bearingDegrees(degrees), string(LocationFormat.compassPoint(degrees).labelRes)),
                )
            },
            location.timestampUtc?.let { PdfFact(string(R.string.export_pdf_gps_time), GPS_TIME.format(it.atOffset(ZoneOffset.UTC)), mono = true) },
        )
        val map = when (mapMode) {
            PdfMapMode.Map -> PdfMap.Snapshot(string(R.string.map_attribution))
            PdfMapMode.Off -> PdfMap.Panel(string(R.string.export_pdf_map_off))
            PdfMapMode.Offline -> PdfMap.Panel(string(R.string.export_pdf_map_offline))
            PdfMapMode.Failed -> PdfMap.Panel(string(R.string.export_pdf_map_failed))
        }
        return PdfLocationBlock(
            title = title,
            coordinates = coordinates,
            coordinatesDms = LocationFormat.degreesMinutesSeconds(location.latitude, location.longitude),
            facts = facts,
            map = map,
            message = null,
        )
    }

    private fun fileSize(bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    private fun date(day: LocalDate): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(day)

    private fun string(id: Int): String = resources.getString(id)

    private companion object {
        const val MAX_FINDING_TAGS = 6
        val GPS_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss 'UTC'", Locale.ROOT)
    }
}
