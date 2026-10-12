package io.github.fishpimp.exiflab.data.export.pdf

/**
 * What one photo's part of the report says, already formatted and localized. Built from a
 * metadata report by [PdfSectionBuilder]; laid out by [PdfReportLayout].
 */
data class PdfPhotoSection(
    /** Small accent line above the title, e.g. "Metadata report". */
    val overline: String,
    /** The file name. */
    val title: String,
    /** "JPEG · 8.4 MB". */
    val subtitle: String?,
    /** Width / height of the photo as shown, for the thumbnail frame; null draws a square frame. */
    val thumbnailAspect: Float?,
    /** Shown in place of the camera when the file names none. */
    val camera: String,
    val lens: String?,
    /** Aperture, shutter speed, ISO and focal length, whichever are known. */
    val exposure: List<PdfStat>,
    /** Capture time, resolution, size and the like, as label and value. */
    val facts: List<PdfFact>,
    val privacy: PdfPrivacy,
    val location: PdfLocationBlock,
    val tagsTitle: String,
    val tables: List<PdfTable>,
    val warnings: PdfNotes?,
)

data class PdfStat(val value: String, val label: String)

data class PdfFact(val label: String, val value: String, val mono: Boolean = false)

data class PdfPrivacy(
    val title: String,
    /** One line above the findings, or the whole message when there are none. */
    val intro: String,
    val findings: List<PdfFinding>,
)

/** One privacy category: "Location" with "2 tags: GPS Latitude, GPS Longitude". */
data class PdfFinding(val label: String, val detail: String)

data class PdfLocationBlock(
    val title: String,
    /** The position as a big mono line ("55.605900, 13.002000"), or null when there is none. */
    val coordinates: String?,
    /** The same position in degrees, minutes and seconds. */
    val coordinatesDms: String?,
    /** Altitude, direction and GPS time, whichever are known. */
    val facts: List<PdfFact>,
    val map: PdfMap,
    /** Shown when there is no position: why. */
    val message: String?,
)

/** What sits in the map slot of the location block. */
sealed interface PdfMap {
    /** No position, so no map and no panel. */
    data object None : PdfMap

    /** A map image with its required credit line. */
    data class Snapshot(val attribution: String) : PdfMap

    /** A drawn coordinate panel with a note saying why there is no map. */
    data class Panel(val note: String) : PdfMap
}

data class PdfColumn(val header: String, val weight: Float, val style: PdfTextStyle = PdfStyles.Cell)

/** One metadata directory as a table. */
data class PdfTable(
    val title: String,
    /** "34 tags". */
    val detail: String,
    /** Repeated after the title on the pages a long table continues on, e.g. "(continued)". */
    val continued: String,
    val columns: List<PdfColumn>,
    val rows: List<List<String>>,
)

data class PdfNotes(val title: String, val lines: List<String>)

/** The first page of a report with several photos. */
data class PdfSummary(
    val overline: String,
    val title: String,
    val subtitle: String,
    val stats: List<PdfStat>,
    val table: PdfTable,
    /** Photos that could not be read, if any. */
    val notIncluded: PdfNotes?,
)
