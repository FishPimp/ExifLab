package io.github.fishpimp.exiflab.metadata

/**
 * Container formats ExifLab understands. [isRaw] formats are never modified in place; edits
 * go to an XMP sidecar instead.
 */
enum class ImageFormat(
    val displayName: String,
    val mimeType: String,
    val isRaw: Boolean,
    val extensions: Set<String>,
) {
    Jpeg("JPEG", "image/jpeg", false, setOf("jpg", "jpeg", "jpe", "jfif")),
    Png("PNG", "image/png", false, setOf("png")),
    WebP("WebP", "image/webp", false, setOf("webp")),
    Heif("HEIF", "image/heif", false, setOf("heic", "heif", "hif")),
    Avif("AVIF", "image/avif", false, setOf("avif")),
    Tiff("TIFF", "image/tiff", false, setOf("tif", "tiff")),
    Dng("DNG", "image/x-adobe-dng", true, setOf("dng")),
    Cr2("Canon CR2", "image/x-canon-cr2", true, setOf("cr2")),
    Cr3("Canon CR3", "image/x-canon-cr3", true, setOf("cr3")),
    Nef("Nikon NEF", "image/x-nikon-nef", true, setOf("nef", "nrw")),
    Arw("Sony ARW", "image/x-sony-arw", true, setOf("arw", "srf", "sr2")),
    Raf("Fujifilm RAF", "image/x-fuji-raf", true, setOf("raf")),
    Orf("Olympus ORF", "image/x-olympus-orf", true, setOf("orf")),
    Rw2("Panasonic RW2", "image/x-panasonic-rw2", true, setOf("rw2")),
    Pef("Pentax PEF", "image/x-pentax-pef", true, setOf("pef")),
    Srw("Samsung SRW", "image/x-samsung-srw", true, setOf("srw")),
    Unknown("Unknown", "application/octet-stream", false, emptySet());

    companion object {
        /** Bytes [sniff] needs to identify every supported format. */
        const val SNIFF_LENGTH = 32

        private val heifBrands = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1", "mif2")
        private val avifBrands = setOf("avif", "avis")

        fun fromExtension(fileName: String?): ImageFormat {
            val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
            if (ext.isEmpty()) return Unknown
            return entries.firstOrNull { ext in it.extensions } ?: Unknown
        }

        /**
         * Identifies a file from its first bytes. TIFF-based raw formats share the TIFF
         * signature, so [fileName] breaks ties for them.
         */
        fun sniff(header: ByteArray, fileName: String? = null): ImageFormat {
            val byExtension = fromExtension(fileName)
            fun at(offset: Int, vararg bytes: Int) =
                header.size >= offset + bytes.size && bytes.indices.all { header[offset + it] == bytes[it].toByte() }
            fun ascii(offset: Int, length: Int) =
                if (header.size >= offset + length) String(header, offset, length, Charsets.ISO_8859_1) else ""

            return when {
                at(0, 0xFF, 0xD8, 0xFF) -> Jpeg
                at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> Png
                ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> WebP
                ascii(0, 15) == "FUJIFILMCCD-RAW" -> Raf
                ascii(4, 4) == "ftyp" -> {
                    val major = ascii(8, 4)
                    when {
                        major == "crx " -> Cr3
                        major in avifBrands -> Avif
                        major in heifBrands -> if (byExtension == Avif) Avif else Heif
                        else -> Unknown
                    }
                }
                ascii(0, 4) == "IIRO" || ascii(0, 4) == "IIRS" || ascii(0, 4) == "MMOR" -> Orf
                at(0, 0x49, 0x49, 0x55, 0x00) -> Rw2
                at(0, 0x49, 0x49, 0x2A, 0x00) || at(0, 0x4D, 0x4D, 0x00, 0x2A) -> when {
                    ascii(8, 2) == "CR" -> Cr2
                    byExtension.isTiffBased() -> byExtension
                    else -> Tiff
                }
                else -> Unknown
            }
        }

        private fun ImageFormat.isTiffBased() = this in setOf(Tiff, Dng, Cr2, Nef, Arw, Orf, Pef, Srw)
    }
}
