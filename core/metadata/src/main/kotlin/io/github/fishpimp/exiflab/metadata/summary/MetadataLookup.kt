package io.github.fishpimp.exiflab.metadata.summary

import com.drew.metadata.Directory
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.PanasonicRawIFD0Directory
import com.drew.metadata.xmp.XmpDirectory
import io.github.fishpimp.exiflab.metadata.mapping.DirectoryCatalog
import io.github.fishpimp.exiflab.metadata.mapping.ValueText
import io.github.fishpimp.exiflab.metadata.mapping.XmpProperties

/** Cross-directory lookups over parsed metadata, used to build the summary, dates and location. */
internal class MetadataLookup(val metadata: Metadata) {
    /** All directories of type [T] in parse order. */
    inline fun <reified T : Directory> all(): List<T> = metadata.getDirectoriesOfType(T::class.java).toList()

    /**
     * Directories holding the main image's Exif tags, in lookup order: IFD0, the Exif SubIFD,
     * then RW2's IFD0. Thumbnail and RAW image-data IFDs are excluded.
     */
    val exif: List<Directory> by lazy {
        all<ExifIFD0Directory>() +
            all<ExifSubIFDDirectory>().filterNot(DirectoryCatalog::isImageSubIfd) +
            all<PanasonicRawIFD0Directory>()
    }

    /** XMP properties by path; the first packet wins when several define one. */
    val xmp: Map<String, String> by lazy {
        val map = LinkedHashMap<String, String>()
        for (directory in all<XmpDirectory>()) {
            for (property in XmpProperties.of(directory)) map.putIfAbsent(property.path, property.value)
        }
        map
    }

    /** The first main-image Exif directory containing [tag]. */
    fun exif(tag: Int): Directory? = exif.firstOrNull { it.containsTag(tag) }

    /** Trimmed, sanitized XMP value at [path], or null when absent or blank. */
    fun xmp(path: String): String? = xmp[path]?.let { ValueText.sanitize(it).trim().ifEmpty { null } }
}
