package io.github.fishpimp.exiflab.metadata.export

import io.github.fishpimp.exiflab.metadata.model.MetadataReport

/**
 * What goes into a CSV or JSON export of [MetadataReport]s.
 *
 * @property includeRawValues adds each tag's value as stored next to its readable value. Off, the
 *   CSV `raw_value` column stays empty and the JSON `raw` keys are left out.
 * @property redactSensitive replaces the values of privacy-sensitive tags with [REDACTED_VALUE] and
 *   drops the decoded location; see [redactedForExport].
 */
data class ExportOptions(
    val includeRawValues: Boolean = true,
    val redactSensitive: Boolean = false,
)

/** What a redacted value reads in every export format. */
const val REDACTED_VALUE = "[removed]"

/**
 * This report as a redacted export shows it: every tag classified as sensitive keeps its name
 * but loses its value (readable and raw), and the decoded location is dropped. The location
 * status and the list of sensitive findings stay, so the export still says what was there.
 */
fun MetadataReport.redactedForExport(): MetadataReport = copy(
    location = null,
    directories = directories.map { directory ->
        if (directory.tags.none { it.sensitivity != null }) {
            directory
        } else {
            directory.copy(
                tags = directory.tags.map { tag ->
                    if (tag.sensitivity == null) tag else tag.copy(displayValue = REDACTED_VALUE, rawValue = REDACTED_VALUE)
                },
            )
        }
    },
)

/** The report an export with [options] writes. */
internal fun MetadataReport.forExport(options: ExportOptions): MetadataReport =
    if (options.redactSensitive) redactedForExport() else this

/**
 * One camera name from make and model, without repeating the brand: "Canon" and "Canon EOS R5"
 * give "Canon EOS R5", "FUJIFILM" and "X-T5" give "FUJIFILM X-T5". Null when both are missing.
 */
fun cameraLabel(make: String?, model: String?): String? {
    val cleanMake = make?.trim()?.takeIf { it.isNotEmpty() }
    val cleanModel = model?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        cleanMake == null -> cleanModel
        cleanModel == null -> cleanMake
        cleanModel.startsWith(cleanMake.substringBefore(' '), ignoreCase = true) -> cleanModel
        else -> "$cleanMake $cleanModel"
    }
}
