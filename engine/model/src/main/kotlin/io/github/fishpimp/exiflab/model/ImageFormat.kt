package io.github.fishpimp.exiflab.model

import kotlinx.serialization.Serializable

/** How ExifLab can persist edits for a format. */
@Serializable
public enum class WriteSupport {
    /** Metadata is rewritten inside the file without touching image data. */
    IN_FILE,

    /** The file is never opened for writing; edits go to an XMP sidecar next to it. */
    SIDECAR,

    /** Read-only. */
    NONE,
}

/**
 * Formats ExifLab recognises. Detection is always by magic bytes; [extensions] and [mimeType] are only used
 * for naming output files and for intent filters.
 */
@Serializable
public enum class ImageFormat(
    public val displayName: String,
    public val extensions: List<String>,
    public val mimeType: String,
    public val writeSupport: WriteSupport,
    public val isRaw: Boolean,
) {
    JPEG("JPEG", listOf("jpg", "jpeg"), "image/jpeg", WriteSupport.IN_FILE, false),
    PNG("PNG", listOf("png"), "image/png", WriteSupport.IN_FILE, false),
    WEBP("WebP", listOf("webp"), "image/webp", WriteSupport.IN_FILE, false),
    HEIF("HEIF", listOf("heic", "heif", "hif"), "image/heif", WriteSupport.IN_FILE, false),
    AVIF("AVIF", listOf("avif"), "image/avif", WriteSupport.IN_FILE, false),
    TIFF("TIFF", listOf("tif", "tiff"), "image/tiff", WriteSupport.IN_FILE, false),
    DNG("DNG", listOf("dng"), "image/x-adobe-dng", WriteSupport.IN_FILE, true),
    CR2("Canon CR2", listOf("cr2"), "image/x-canon-cr2", WriteSupport.SIDECAR, true),
    CR3("Canon CR3", listOf("cr3"), "image/x-canon-cr3", WriteSupport.SIDECAR, true),
    CRW("Canon CRW", listOf("crw"), "image/x-canon-crw", WriteSupport.SIDECAR, true),
    NEF("Nikon NEF", listOf("nef"), "image/x-nikon-nef", WriteSupport.SIDECAR, true),
    NRW("Nikon NRW", listOf("nrw"), "image/x-nikon-nrw", WriteSupport.SIDECAR, true),
    ARW("Sony ARW", listOf("arw", "srf", "sr2"), "image/x-sony-arw", WriteSupport.SIDECAR, true),
    ORF("Olympus ORF", listOf("orf"), "image/x-olympus-orf", WriteSupport.SIDECAR, true),
    RW2("Panasonic RW2", listOf("rw2", "rwl"), "image/x-panasonic-rw2", WriteSupport.SIDECAR, true),
    PEF("Pentax PEF", listOf("pef"), "image/x-pentax-pef", WriteSupport.SIDECAR, true),
    SRW("Samsung SRW", listOf("srw"), "image/x-samsung-srw", WriteSupport.SIDECAR, true),
    RAF("Fujifilm RAF", listOf("raf"), "image/x-fuji-raf", WriteSupport.SIDECAR, true),
    UNKNOWN("Unknown", emptyList(), "application/octet-stream", WriteSupport.NONE, false),
    ;

    public val primaryExtension: String get() = extensions.firstOrNull() ?: "bin"

    public companion object {
        /** Best-effort guess from a file name, only used when no bytes are available (e.g. sidecar pairing). */
        public fun fromExtension(name: String): ImageFormat {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions } ?: UNKNOWN
        }
    }
}

/** User-selectable handling of DNG files (in-file by default, see docs/spike-format-matrix.md). */
@Serializable
public enum class DngWriteMode { IN_FILE, SIDECAR }

/** Sidecar naming conventions. */
@Serializable
public enum class SidecarNaming {
    /** `IMG_0001.xmp` - Adobe Lightroom, Bridge, Capture One. */
    BASENAME,

    /** `IMG_0001.CR2.xmp` - darktable, digiKam. */
    FULL_NAME,
    ;

    public fun sidecarName(imageFileName: String): String = when (this) {
        BASENAME -> imageFileName.substringBeforeLast('.', imageFileName) + ".xmp"
        FULL_NAME -> "$imageFileName.xmp"
    }
}
