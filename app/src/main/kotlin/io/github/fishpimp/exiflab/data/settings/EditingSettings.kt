package io.github.fishpimp.exiflab.data.settings

import java.util.concurrent.TimeUnit

/** How long backups of edited originals are kept before they are pruned automatically. */
enum class BackupRetention(val days: Int?) {
    Days7(7),
    Days30(30),
    Days90(90),

    /** Never pruned by age; only the size cap still applies to older backups of a photo. */
    Forever(null),
    ;

    /** The retention in milliseconds, or null for [Forever]. */
    val millis: Long? get() = days?.let { TimeUnit.DAYS.toMillis(it.toLong()) }

    companion object {
        val Default = Days30
    }
}

/** How the XMP sidecar of a RAW file is named. */
enum class SidecarNaming {
    /** `IMG_1234.xmp`: the photo's name without its extension (Lightroom, Capture One). */
    BaseName,

    /** `IMG_1234.RAF.xmp`: the full file name plus `.xmp` (darktable, digiKam). */
    FullName,
    ;

    /** The sidecar name for a photo called [photoName]. Names without an extension just get `.xmp`. */
    fun sidecarName(photoName: String): String {
        val base = when (this) {
            BaseName -> photoName.substringBeforeLast('.').takeIf { photoName.contains('.') && it.isNotEmpty() } ?: photoName
            FullName -> photoName
        }
        return "$base.$SIDECAR_EXTENSION"
    }

    companion object {
        val Default = BaseName
        const val SIDECAR_EXTENSION = "xmp"
    }
}

/** Total size backups may take before the oldest are pruned: 2 GB (decimal, as Android shows sizes). */
const val DEFAULT_BACKUP_SIZE_CAP_BYTES: Long = 2_000_000_000L
