package io.github.fishpimp.exiflab.data.export

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** File names suggested in the system "Save as" dialog; the user can still change them there. */
object ExportFileNames {
    private const val SUFFIX = "-metadata"
    private const val MAX_BASE_LENGTH = 80

    /** "IMG_1234-metadata.pdf" for "IMG_1234.JPG"; "photo-metadata.pdf" without a usable name. */
    fun forPhoto(photoName: String?, format: ExportFormat): String {
        val base = photoName?.let(::withoutExtension)?.let(::sanitize).orEmpty().ifEmpty { "photo" }
        return "$base$SUFFIX.${format.extension}"
    }

    /**
     * For several photos: "<folder>-metadata.csv" when they come from one folder, otherwise
     * "photos-metadata-2026-10-12.csv".
     */
    fun forSelection(folderName: String?, format: ExportFormat, today: LocalDate): String {
        val folder = folderName?.let(::sanitize).orEmpty()
        val base = folder.ifEmpty { "photos$SUFFIX-${today.format(DateTimeFormatter.ISO_LOCAL_DATE)}" }
        return if (folder.isEmpty()) "$base.${format.extension}" else "$base$SUFFIX.${format.extension}"
    }

    /** "IMG_1234" for "IMG_1234.JPG", the whole name when it has no extension or is only one (".hidden"). */
    internal fun withoutExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }

    /**
     * A name every document provider accepts: no path separators, reserved or control characters,
     * no leading dots or trailing spaces and dots, at most [MAX_BASE_LENGTH] characters.
     */
    internal fun sanitize(name: String): String {
        val cleaned = buildString(name.length) {
            name.forEach { char ->
                append(if (char < ' ' || char in RESERVED) '_' else char)
            }
        }
        val short = cleaned.trim().trimStart('.').trimEnd('.', ' ').take(MAX_BASE_LENGTH)
        // Never end on half of a surrogate pair (an emoji cut in two).
        return short.dropLastWhile { it.isHighSurrogate() }.trimEnd('.', ' ')
    }

    private val RESERVED = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u007F')
}
