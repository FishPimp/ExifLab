package io.github.fishpimp.exiflab.metadata.export

import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.PhotoSummary
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** Who wrote an export and when, for the `exiflab` header. */
data class ExportInfo(
    /** The app version, e.g. "0.1.0". */
    val appVersion: String,
    val exportedAt: OffsetDateTime,
)

/**
 * Writes metadata as JSON in a stable, documented schema (see docs/EXPORT.md). Streams: call
 * [begin], then [writeReport] once per photo, then [end]; nothing is kept between photos.
 *
 * ```
 * {
 *   "exiflab": { "version": "0.1.0", "schema": 1, "exportedAt": "2026-10-12T14:03:22+02:00",
 *                "redacted": false, "rawValues": true },
 *   "files": [ {
 *     "name": "IMG_1234.jpg", "size": 3145728, "format": "JPEG", "mimeType": "image/jpeg",
 *     "summary": { "camera", "make", "model", "lens", "fNumber", "exposureTime", "exposureTimeSeconds",
 *                  "iso", "focalLengthMm", "focalLength35mm", "exposureBiasEv", "flashFired", "width",
 *                  "height", "orientation", "capturedAt", "utcOffset", "utcOffsetSource", "software",
 *                  "colorProfile" },
 *     "location": { "lat", "lon", "alt", "direction", "gpsTime" } | null,
 *     "locationStatus": "Present" | "Absent" | "Redacted",
 *     "sensitive": [ { "category": "Location", "tags": ["gps:0x0002", ...] } ],
 *     "directories": [ { "id", "name", "group", "tags": [ { "key", "id", "hexId", "name", "value", "raw", "sensitive" } ] } ],
 *     "warnings": [ "..." ]
 *   } ]
 * }
 * ```
 * Every summary key is always present (null when unknown). `capturedAt` is ISO 8601 with the
 * UTC offset when the file records one ("2026-06-14T17:42:07+02:00"), local time without it
 * otherwise. `raw` is left out unless [ExportOptions.includeRawValues].
 */
class MetadataJsonWriter(
    out: Appendable,
    private val info: ExportInfo,
    private val options: ExportOptions = ExportOptions(),
) {
    private val json = JsonOut(out)
    private var state = State.New

    private enum class State { New, Open, Closed }

    /** Writes the header and opens the `files` array. */
    fun begin() {
        check(state == State.New) { "begin() was already called" }
        state = State.Open
        json.beginObject()
        json.name("exiflab").beginObject()
            .field("version", info.appVersion)
            .field("schema", SCHEMA_VERSION)
            .field("exportedAt", info.exportedAt.format(OFFSET_DATE_TIME))
            .field("redacted", options.redactSensitive)
            .field("rawValues", options.includeRawValues)
            .endObject()
        json.name("files").beginArray()
    }

    /** Adds one photo under [fileName] (the report's own name by default). */
    fun writeReport(report: MetadataReport, fileName: String? = report.fileName) {
        check(state == State.Open) { "Call begin() first" }
        val exported = report.forExport(options)
        json.beginObject()
            .field("name", fileName)
            .field("size", exported.fileSize)
            .field("format", exported.format.displayName)
            .field("mimeType", exported.format.mimeType)
        writeSummary(exported.summary)
        writeLocation(exported)
        json.field("locationStatus", exported.locationStatus.name)
        json.name("sensitive").beginArray()
        exported.sensitiveFindings.forEach { finding ->
            json.beginObject().field("category", finding.category.name)
            json.name("tags").beginArray()
            finding.tagKeys.forEach { json.value(it) }
            json.endArray().endObject()
        }
        json.endArray()
        json.name("directories").beginArray()
        exported.directories.forEach { directory ->
            json.beginObject()
                .field("id", directory.id)
                .field("name", directory.name)
                .field("group", directory.group.name)
            json.name("tags").beginArray()
            directory.tags.forEach { tag ->
                json.beginObject()
                    .field("key", tag.key)
                    .field("id", tag.id)
                    .field("hexId", tag.hexId)
                    .field("name", tag.name)
                    .field("value", tag.displayValue)
                if (options.includeRawValues) json.field("raw", tag.rawValue)
                json.field("sensitive", tag.sensitivity?.name).endObject()
            }
            json.endArray().endObject()
        }
        json.endArray()
        json.name("warnings").beginArray()
        exported.warnings.forEach { json.value(it) }
        json.endArray()
        json.endObject()
    }

    /** Closes the `files` array and the document. */
    fun end() {
        check(state == State.Open) { "Call begin() first" }
        state = State.Closed
        json.endArray().endObject().finish()
    }

    private fun writeSummary(summary: PhotoSummary) {
        json.name("summary").beginObject()
            .field("camera", cameraLabel(summary.make, summary.model))
            .field("make", summary.make)
            .field("model", summary.model)
            .field("lens", summary.lens)
            .field("fNumber", summary.fNumber)
            .field("exposureTime", summary.exposureTime?.toString())
            .field("exposureTimeSeconds", summary.exposureTime?.value)
            .field("iso", summary.iso)
            .field("focalLengthMm", summary.focalLengthMm)
            .field("focalLength35mm", summary.focalLength35mm)
            .field("exposureBiasEv", summary.exposureBiasEv)
            .field("flashFired", summary.flashFired)
            .field("width", summary.width)
            .field("height", summary.height)
            .field("orientation", summary.orientation)
            .field("capturedAt", capturedAtIso(summary))
            .field("utcOffset", summary.utcOffset?.let(::offsetIso))
            .field("utcOffsetSource", summary.utcOffsetSource?.name)
            .field("software", summary.software)
            .field("colorProfile", summary.colorProfile)
            .endObject()
    }

    private fun writeLocation(report: MetadataReport) {
        val location = report.location
        if (location == null) {
            json.nullField("location")
            return
        }
        json.name("location").beginObject()
            .field("lat", location.latitude)
            .field("lon", location.longitude)
            .field("alt", location.altitudeMeters)
            .field("direction", location.directionDegrees)
            .field("gpsTime", location.timestampUtc?.let(::instantIso))
            .endObject()
    }

    companion object {
        /** Bumped only for incompatible changes; new keys can appear without it. */
        const val SCHEMA_VERSION = 1
        const val MIME_TYPE = "application/json"

        /** "2026-06-14T17:42:07+02:00" with an offset, "2026-06-14T17:42:07" without. Seconds always shown. */
        fun capturedAtIso(summary: PhotoSummary): String? {
            val local = summary.capturedAt ?: return null
            val offset = summary.utcOffset
            return if (offset == null) {
                local.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            } else {
                OffsetDateTime.of(local, offset).format(OFFSET_DATE_TIME)
            }
        }

        /** "+02:00", "-03:30", "+00:00" (never "Z", so it reads like the other offsets). */
        fun offsetIso(offset: ZoneOffset): String = if (offset.totalSeconds == 0) "+00:00" else offset.id

        /** ISO 8601 date and time with seconds and a "+00:00"-style offset (not "Z"). */
        private val OFFSET_DATE_TIME: DateTimeFormatter = DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .appendOffset("+HH:MM", "+00:00")
            .toFormatter(Locale.ROOT)

        private fun instantIso(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant)
    }
}
