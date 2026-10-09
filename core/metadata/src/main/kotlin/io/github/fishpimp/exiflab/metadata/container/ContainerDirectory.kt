package io.github.fishpimp.exiflab.metadata.container

import com.drew.metadata.Directory
import com.drew.metadata.TagDescriptor

/**
 * A metadata-extractor directory for container facts ExifLab parses itself (RAF header, CR3 boxes),
 * so they flow through the same mapping as library directories. [id] becomes the stable
 * directory id; [describe] can render a tag more readably than its stored value.
 */
internal class ContainerDirectory(
    val id: String,
    private val displayName: String,
    tagNames: Map<Int, String>,
    describe: (ContainerDirectory, Int) -> String? = { _, _ -> null },
) : Directory() {
    private val tagNameMap = HashMap(tagNames)

    init {
        setDescriptor(object : TagDescriptor<ContainerDirectory>(this) {
            override fun getDescription(tagType: Int): String? = describe(_directory, tagType) ?: super.getDescription(tagType)
        })
    }

    override fun getName(): String = displayName

    override fun getTagNameMap(): HashMap<Int, String> = tagNameMap
}
