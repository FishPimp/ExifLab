package io.github.fishpimp.exiflab.metadata.report

import com.drew.metadata.Directory
import com.drew.metadata.ErrorDirectory
import com.drew.metadata.Metadata
import com.drew.metadata.xmp.XmpDirectory
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.mapping.DirectoryCatalog
import io.github.fishpimp.exiflab.metadata.mapping.DirectoryPlacement
import io.github.fishpimp.exiflab.metadata.mapping.TagMapper
import io.github.fishpimp.exiflab.metadata.mapping.XmpProperties
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.MetadataDirectory
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.MetadataTag
import io.github.fishpimp.exiflab.metadata.model.SensitiveFinding
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory
import io.github.fishpimp.exiflab.metadata.summary.CaptureTimeResolver
import io.github.fishpimp.exiflab.metadata.summary.LocationResolver
import io.github.fishpimp.exiflab.metadata.summary.MetadataLookup
import io.github.fishpimp.exiflab.metadata.summary.SummaryBuilder
import java.util.Locale

/** Turns parsed metadata-extractor output into the public [MetadataReport]. */
internal class ReportAssembler(
    private val format: ImageFormat,
    private val fileName: String?,
    private val fileSize: Long?,
    private val metadata: Metadata,
    private val parseWarnings: List<String>,
) {
    fun assemble(): MetadataReport {
        val lookup = MetadataLookup(metadata)
        val directories = directories()
        val location = LocationResolver(lookup).resolve()
        val capture = CaptureTimeResolver(lookup, location.gpsTimestamp).resolve()
        return MetadataReport(
            format = format,
            fileName = fileName,
            fileSize = fileSize,
            summary = SummaryBuilder(lookup, format).build(capture),
            location = location.location,
            locationStatus = location.status,
            directories = directories,
            sensitiveFindings = findings(directories),
            warnings = (parseWarnings + directoryErrors()).distinct(),
        )
    }

    private fun directories(): List<MetadataDirectory> {
        val placed = metadata.directories
            .filterNot { it is ErrorDirectory }
            .withIndex()
            .sortedWith(compareBy({ placementOf(it.value).group.ordinal }, { placementOf(it.value).rank }, { it.index }))
            .map { it.value }
        val usedIds = HashMap<String, Int>()
        val mapped = placed.mapNotNull { directory ->
            val placement = placementOf(directory)
            if (!hasContent(directory)) return@mapNotNull null
            val count = (usedIds[placement.baseId] ?: 0) + 1
            usedIds[placement.baseId] = count
            val id = if (count == 1) placement.baseId else "${placement.baseId}-$count"
            val tags = TagMapper.map(directory, id, placement.group)
            if (tags.isEmpty()) null else MetadataDirectory(id, placement.name, placement.group, tags)
        }
        val all = (mapped + fileDirectory()).sortedBy { it.group.ordinal }
        return withUniqueKeys(all)
    }

    private val placements = HashMap<Directory, DirectoryPlacement>()

    private fun placementOf(directory: Directory): DirectoryPlacement = placements.getOrPut(directory) { DirectoryCatalog.place(directory) }

    private fun hasContent(directory: Directory): Boolean =
        if (directory is XmpDirectory) XmpProperties.of(directory).isNotEmpty() else directory.tagCount > 0

    /** Synthetic "File" directory with what is known about the file itself. */
    private fun fileDirectory(): MetadataDirectory {
        val id = "file"
        fun field(name: String, display: String, raw: String = display) =
            MetadataTag(key = "$id:$name", directoryId = id, id = null, name = name, displayValue = display, rawValue = raw)
        val tags = listOfNotNull(
            fileName?.let { field("File Name", it) },
            fileSize?.let { field("File Size", humanSize(it), it.toString()) },
            field("File Type", format.displayName, format.name),
            field("MIME Type", format.mimeType),
        )
        return MetadataDirectory(id, "File", DirectoryGroup.File, tags)
    }

    /** Tag keys must be unique per report; vendor directories occasionally repeat a name. */
    private fun withUniqueKeys(directories: List<MetadataDirectory>): List<MetadataDirectory> {
        val seen = HashSet<String>()
        return directories.map { directory ->
            directory.copy(tags = directory.tags.map { tag ->
                var key = tag.key
                var n = 2
                while (!seen.add(key)) key = "${tag.key}#${n++}"
                if (key == tag.key) tag else tag.copy(key = key)
            })
        }
    }

    private fun findings(directories: List<MetadataDirectory>): List<SensitiveFinding> {
        val tags = directories.flatMap { it.tags }
        return SensitivityCategory.entries.mapNotNull { category ->
            tags.filter { it.sensitivity == category }.map { it.key }.takeIf { it.isNotEmpty() }?.let { SensitiveFinding(category, it) }
        }
    }

    private fun directoryErrors(): List<String> = metadata.directories.flatMap { directory ->
        directory.errors.map { error -> if (directory is ErrorDirectory) error else "${directory.name}: $error" }
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1000) return "$bytes bytes"
        val units = listOf("kB", "MB", "GB", "TB")
        var value = bytes / 1000.0
        var unit = 0
        while (value >= 1000 && unit < units.lastIndex) {
            value /= 1000
            unit++
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
    }
}
