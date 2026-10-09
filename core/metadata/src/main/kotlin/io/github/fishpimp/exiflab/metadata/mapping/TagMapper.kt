package io.github.fishpimp.exiflab.metadata.mapping

import com.drew.metadata.Directory
import com.drew.metadata.png.PngDirectory
import com.drew.metadata.xmp.XmpDirectory
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.MetadataTag
import io.github.fishpimp.exiflab.metadata.summary.textEntries

/** Converts the tags of one metadata-extractor directory into report tags. */
internal object TagMapper {
    /** Undecoded byte arrays longer than this are summarized by size instead of shown as hex. */
    const val BLOB_THRESHOLD = 256

    private val genericArrayDescription = Regex("""^\[\d+ values?]$""")

    /**
     * Tags of [directory] under report id [directoryId]. Container and file directories hold
     * structural fields rather than numbered tags, so their tags get no numeric id.
     */
    fun map(directory: Directory, directoryId: String, group: DirectoryGroup): List<MetadataTag> = when (directory) {
        is XmpDirectory -> xmpTags(XmpProperties.of(directory), directoryId)
        else -> {
            val numbered = group != DirectoryGroup.Container && group != DirectoryGroup.File
            directory.tags.flatMap { tag ->
                if (directory is PngDirectory && tag.tagType == PngDirectory.TAG_TEXTUAL_DATA) {
                    pngTextTags(directory, directoryId)
                } else {
                    listOf(tag(directory, tag.tagType, directoryId, numbered))
                }
            }
        }
    }

    fun xmpTags(properties: List<XmpProperty>, directoryId: String): List<MetadataTag> = properties.map { property ->
        val value = ValueText.sanitize(property.value)
        MetadataTag(
            key = "$directoryId:${property.path}",
            directoryId = directoryId,
            id = null,
            name = property.path,
            displayValue = value,
            rawValue = value,
            sensitivity = SensitivityClassifier.classifyXmp(property.path),
        )
    }

    private fun tag(directory: Directory, tagType: Int, directoryId: String, numbered: Boolean): MetadataTag {
        val name = ValueText.sanitize(directory.getTagName(tagType))
        val value = directory.getObject(tagType)
        // Descriptors decode untrusted bytes; a failing one must not lose the tag.
        val description = runCatching { directory.getDescription(tagType) }.getOrNull()
            ?.let(ValueText::sanitize)?.takeIf { it.isNotBlank() }
        // Bytes no descriptor could decode: name the size; past BLOB_THRESHOLD the hex is noise too.
        val undecodedSize = (value as? ByteArray)
            ?.takeIf { description == null || genericArrayDescription.matches(description) }
            ?.size
        val rawValue = if (undecodedSize != null && undecodedSize > BLOB_THRESHOLD) "($undecodedSize bytes)" else ValueText.raw(value)
        val displayValue = if (undecodedSize != null) "Binary data ($undecodedSize bytes)" else description ?: rawValue
        return MetadataTag(
            key = "$directoryId:${if (numbered) tagType.toString() else name}",
            directoryId = directoryId,
            id = if (numbered) tagType else null,
            name = name,
            displayValue = displayValue,
            rawValue = rawValue,
            sensitivity = SensitivityClassifier.classify(directory, tagType, name),
        )
    }

    /** One tag per PNG text entry, named by its keyword ("Comment", "Author", ...). */
    private fun pngTextTags(directory: PngDirectory, directoryId: String): List<MetadataTag> =
        directory.textEntries().map { (keyword, text) ->
            val name = ValueText.sanitize(keyword)
            val value = ValueText.sanitize(text)
            MetadataTag(
                key = "$directoryId:$name",
                directoryId = directoryId,
                id = null,
                name = name,
                displayValue = value,
                rawValue = value,
                sensitivity = SensitivityClassifier.classify(directory, PngDirectory.TAG_TEXTUAL_DATA, name),
            )
        }
}
