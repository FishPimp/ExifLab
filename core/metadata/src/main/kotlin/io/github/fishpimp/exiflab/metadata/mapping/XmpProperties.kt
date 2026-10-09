package io.github.fishpimp.exiflab.metadata.mapping

import com.adobe.internal.xmp.XMPException
import com.adobe.internal.xmp.options.IteratorOptions
import com.adobe.internal.xmp.properties.XMPPropertyInfo
import com.drew.metadata.xmp.XmpDirectory

/** One XMP leaf property, named by its qualified path, e.g. "dc:creator[1]" or "exif:GPSLatitude". */
internal class XmpProperty(val path: String, val value: String)

internal object XmpProperties {
    /**
     * Every leaf property of [directory] in document order. Language qualifiers (xml:lang) are
     * omitted; the values of alternative-language arrays still appear as "dc:title[1]" etc.
     */
    fun of(directory: XmpDirectory): List<XmpProperty> {
        val options = IteratorOptions().setJustLeafnodes(true).setOmitQualifiers(true)
        val properties = mutableListOf<XmpProperty>()
        try {
            val iterator = directory.xmpMeta.iterator(options)
            while (iterator.hasNext()) {
                val info = iterator.next() as? XMPPropertyInfo ?: continue
                val path = info.path?.takeIf { it.isNotEmpty() } ?: continue
                val value = info.value ?: continue
                properties += XmpProperty(path, value)
            }
        } catch (_: XMPException) {
            // A malformed tree yields the properties read so far; the parse error is already a directory error.
        }
        return properties
    }
}
