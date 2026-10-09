package io.github.fishpimp.exiflab.data.photos

import io.github.fishpimp.exiflab.metadata.ImageFormat
import java.util.Locale

/** MIME type helpers for opening and recognising photos. */
object PhotoFormats {
    /**
     * MIME types for the "Open files" document picker. RAW types are listed explicitly and
     * `application/octet-stream` is included because many providers report RAW files with a
     * generic type.
     */
    val documentPickerMimeTypes: Array<String> =
        (listOf("image/*") + ImageFormat.entries.filter { it.isRaw }.map { it.mimeType } + "application/octet-stream")
            .toTypedArray()

    private val mimeAliases = mapOf(
        "image/jpg" to ImageFormat.Jpeg,
        "image/pjpeg" to ImageFormat.Jpeg,
        "image/heic" to ImageFormat.Heif,
        "image/heic-sequence" to ImageFormat.Heif,
        "image/heif-sequence" to ImageFormat.Heif,
        "image/dng" to ImageFormat.Dng,
    )

    /**
     * The container format of a file. The extension wins because providers often report RAW
     * files as `application/octet-stream`; the MIME type is the fallback for names without one.
     */
    fun formatOf(mimeType: String?, fileName: String?): ImageFormat {
        val byExtension = ImageFormat.fromExtension(fileName)
        return if (byExtension != ImageFormat.Unknown) byExtension else formatOfMimeType(mimeType)
    }

    /** Maps a MIME type (parameters ignored) to a format, or [ImageFormat.Unknown]. */
    fun formatOfMimeType(mimeType: String?): ImageFormat {
        val normalized = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return ImageFormat.Unknown
        return ImageFormat.entries.firstOrNull { it != ImageFormat.Unknown && it.mimeType == normalized }
            ?: mimeAliases[normalized]
            ?: ImageFormat.Unknown
    }

    /** True for anything ExifLab can show: an `image/` MIME type or a known image extension (RAW included). */
    fun isSupportedImage(mimeType: String?, fileName: String?): Boolean =
        mimeType?.startsWith("image/") == true || ImageFormat.fromExtension(fileName) != ImageFormat.Unknown

    /**
     * Short, untranslated format badge such as "RAF", "HEIC" or "JPG". Uses the extension the
     * user sees in file managers, then the format name, then the MIME subtype. Null when nothing
     * is known.
     */
    fun formatLabel(mimeType: String?, fileName: String?): String? {
        val extension = fileName?.substringAfterLast('.', "").orEmpty()
        if (extension.isNotEmpty() && ImageFormat.fromExtension(fileName) != ImageFormat.Unknown) {
            return extension.uppercase(Locale.ROOT)
        }
        val format = formatOfMimeType(mimeType)
        if (format != ImageFormat.Unknown) return format.displayName.substringAfterLast(' ').uppercase(Locale.ROOT)
        return mimeType
            ?.takeIf { it.startsWith("image/") }
            ?.substringAfter('/')
            ?.substringAfterLast('-')
            ?.takeIf { it.isNotBlank() && it.length <= MAX_LABEL_LENGTH }
            ?.uppercase(Locale.ROOT)
    }

    /** Guesses a MIME type from a file name, for providers that report none. */
    fun mimeTypeFromName(fileName: String?): String? =
        ImageFormat.fromExtension(fileName).takeIf { it != ImageFormat.Unknown }?.mimeType

    private const val MAX_LABEL_LENGTH = 5
}

/** Container format of this photo; see [PhotoFormats.formatOf]. */
val PhotoRef.format: ImageFormat get() = PhotoFormats.formatOf(mimeType, displayName)

/** True for RAW files, which are never modified and need an embedded preview for thumbnails. */
val PhotoRef.isRaw: Boolean get() = format.isRaw

/** Short format badge for thumbnails; see [PhotoFormats.formatLabel]. */
val PhotoRef.formatLabel: String? get() = PhotoFormats.formatLabel(mimeType, displayName)
