package io.github.fishpimp.exiflab.metadata.edit

import com.adobe.internal.xmp.XMPMetaFactory
import java.math.BigInteger
import io.github.fishpimp.exiflab.metadata.mapping.SensitivityClassifier
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.MetadataTag
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory
import io.github.fishpimp.exiflab.metadata.write.ExifChange
import io.github.fishpimp.exiflab.metadata.write.ExifIfd
import io.github.fishpimp.exiflab.metadata.write.ExifValue
import io.github.fishpimp.exiflab.metadata.write.IptcChange
import io.github.fishpimp.exiflab.metadata.write.MetadataBlock
import io.github.fishpimp.exiflab.metadata.write.MetadataChanges
import io.github.fishpimp.exiflab.metadata.write.XmpArrayKind
import io.github.fishpimp.exiflab.metadata.write.XmpChange

internal data class ExifKey(val ifd: ExifIfd, val tag: Int)

/** An EXIF value: [raw] in the report's raw form, [display] human-readable, [value] when the plan set it. */
internal class ExifEntry(val raw: String, val display: String, val value: ExifValue?) {
    fun sameAs(other: ExifEntry): Boolean {
        // "45/1" and "450000/10000" are the same value written differently.
        if (raw != other.raw) return sameRationals(raw, other.raw)
        // Long binary values are shortened in raw form; the decoded text tells them apart.
        return !ExifText.isTruncated(raw) || display == other.display
    }

    private fun sameRationals(a: String, b: String): Boolean {
        val left = rationals(a) ?: return false
        val right = rationals(b) ?: return false
        return left.size == right.size && left.indices.all { i ->
            left[i].first * right[i].second == right[i].first * left[i].second
        }
    }

    private fun rationals(raw: String): List<Pair<BigInteger, BigInteger>>? {
        val parts = raw.trim().split(' ')
        if (parts.isEmpty()) return null
        return parts.map { part ->
            val match = RATIONAL.matchEntire(part) ?: return null
            val denominator = match.groupValues[2].toBigInteger()
            if (denominator.signum() == 0) return null
            match.groupValues[1].toBigInteger() to denominator
        }
    }

    private companion object {
        val RATIONAL = Regex("""^(-?\d+)/(\d+)$""")
    }
}

internal enum class XmpShape { Simple, Seq, Bag, Alt, LangAlt, Struct }

/**
 * A top-level XMP property. [values] are the item values of arrays, the single value of simple
 * properties, and "path=value" leaves of structures. Entries from the file are not [typed]: the
 * planner never writes them back, it only compares against them.
 */
internal class XmpEntry(val prefix: String, val shape: XmpShape, val values: List<String>, val typed: Boolean) {
    val display: String
        get() = if (shape == XmpShape.Struct) plural(values.size, "value") else values.joinToString(", ")

    fun sameAs(other: XmpEntry): Boolean = values == other.values && (shape == XmpShape.Struct) == (other.shape == XmpShape.Struct)
}

internal data class IptcKey(val record: Int, val dataset: Int)

internal class IptcEntry(val values: List<String>, val display: String, val typed: Boolean) {
    fun sameAs(other: IptcEntry): Boolean = values == other.values
}

/**
 * A working copy of a file's EXIF, XMP and IPTC values, built from its [MetadataReport]. The
 * planner applies operations to it in order; [changes] and [diff] then compare it with the
 * original. Block, IFD and namespace removals are tracked separately because they reach data the
 * report does not list one by one.
 */
internal class WorkingMetadata(val report: MetadataReport) {
    private val exifTags = LinkedHashMap<ExifKey, MetadataTag>()
    private val xmpSensitive = HashSet<XmpName>()
    private val iptcTags = LinkedHashMap<IptcKey, MetadataTag>()

    val originalExif: Map<ExifKey, ExifEntry> = readExif()
    val originalXmp: Map<XmpName, XmpEntry> = readXmp()
    val originalIptc: Map<IptcKey, IptcEntry> = readIptc()

    val exif = LinkedHashMap(originalExif)
    val xmp = LinkedHashMap(originalXmp)
    val iptc = LinkedHashMap(originalIptc)

    private val removedBlocks = LinkedHashSet<MetadataBlock>()
    private val droppedIfds = LinkedHashSet<ExifIfd>()
    private val removedNamespaces = LinkedHashSet<String>()
    val warnings = mutableListOf<String>()

    /** Blocks the report shows. A report with parse warnings may miss some, so removals are kept then. */
    private val presentBlocks: Set<MetadataBlock> = buildSet {
        val directories = report.directories
        if (directories.any { it.group == DirectoryGroup.Exif || it.group == DirectoryGroup.Gps || it.group == DirectoryGroup.MakerNote }) add(MetadataBlock.Exif)
        if (directories.any { it.group == DirectoryGroup.MakerNote } || hasRawMakerNote()) add(MetadataBlock.MakerNote)
        if (directories.any { it.id.startsWith("exif-thumbnail") }) add(MetadataBlock.ExifThumbnail)
        if (directories.any { it.group == DirectoryGroup.Xmp }) add(MetadataBlock.Xmp)
        if (directories.any { it.group == DirectoryGroup.Iptc }) addAll(listOf(MetadataBlock.Iptc, MetadataBlock.PhotoshopResources))
        if (directories.any { it.id == "photoshop" || it.id.startsWith("photoshop-") }) add(MetadataBlock.PhotoshopResources)
        if (directories.any { it.group == DirectoryGroup.Icc }) add(MetadataBlock.IccProfile)
        if (directories.any { isCommentDirectory(it.id) }) add(MetadataBlock.Comments)
    }
    /** True when the report had parse problems, so it may not show everything the file holds. */
    val uncertain = report.warnings.isNotEmpty()

    // ---- EXIF ----

    fun exifRaw(ifd: ExifIfd, tag: Int): String? = exif[ExifKey(ifd, tag)]?.raw

    fun hasExif(ifd: ExifIfd, tag: Int): Boolean = ExifKey(ifd, tag) in exif

    fun setExif(ifd: ExifIfd, tag: Int, value: ExifValue) {
        val key = ExifKey(ifd, tag)
        val display = ExifText.describe(ifd, mapOf(tag to value)).getValue(tag)
        exif.remove(key)
        exif[key] = ExifEntry(ExifText.raw(value), display, value)
    }

    /** Removes a tag; IFD pointers drop their IFD and the MakerNote tag removes the MakerNote block. */
    fun removeExif(ifd: ExifIfd, tag: Int) {
        when {
            ifd == ExifIfd.Primary && tag == ExifTags.GPS_IFD_POINTER -> dropIfd(ExifIfd.Gps)
            ifd == ExifIfd.Primary && tag == ExifTags.EXIF_IFD_POINTER -> dropIfd(ExifIfd.Exif)
            ifd == ExifIfd.Exif && tag == ExifTags.INTEROP_IFD_POINTER -> dropIfd(ExifIfd.Interoperability)
            ifd == ExifIfd.Exif && tag == ExifTags.MAKER_NOTE -> removeBlock(MetadataBlock.MakerNote)
            else -> exif.remove(ExifKey(ifd, tag))
        }
    }

    fun hasIfd(ifd: ExifIfd): Boolean = exif.keys.any { it.ifd == ifd }

    /** Removes a whole sub-IFD (GPS, Exif or Interoperability) together with its pointer. */
    fun dropIfd(ifd: ExifIfd) {
        require(ifd != ExifIfd.Primary && ifd != ExifIfd.Thumbnail) { "Use removeBlock for $ifd" }
        val ifds = if (ifd == ExifIfd.Exif) setOf(ExifIfd.Exif, ExifIfd.Interoperability) else setOf(ifd)
        exif.keys.removeAll { it.ifd in ifds }
        droppedIfds += ifds
        if (ifd == ExifIfd.Exif) removedBlocks += MetadataBlock.MakerNote
    }

    // ---- XMP ----

    val hasXmp: Boolean get() = xmp.isNotEmpty()

    fun xmpValue(name: XmpName): String? = xmp[name]?.values?.firstOrNull()

    fun setXmp(name: XmpName, shape: XmpShape, values: List<String>) {
        xmp.remove(name)
        xmp[name] = XmpEntry(prefixOf(name), shape, values, typed = true)
    }

    /** How the reader classifies the top-level property [name] (by its prefixed path, as in the report). */
    fun xmpSensitivity(name: XmpName): SensitivityCategory? {
        val prefix = xmp[name]?.prefix ?: originalXmp[name]?.prefix ?: prefixOf(name)
        return SensitivityClassifier.classifyXmp("$prefix:${name.name}")
    }

    fun removeXmp(name: XmpName) {
        xmp.remove(name)
    }

    fun removeNamespace(namespace: String) {
        removedNamespaces += namespace
        xmp.keys.removeAll { it.namespace == namespace }
    }

    // ---- IPTC ----

    val hasIptc: Boolean get() = iptc.isNotEmpty()

    fun iptcValues(dataset: Int, record: Int = IptcTags.RECORD): List<String>? = iptc[IptcKey(record, dataset)]?.values

    fun setIptc(dataset: Int, values: List<String>, record: Int = IptcTags.RECORD) {
        val key = IptcKey(record, dataset)
        iptc.remove(key)
        iptc[key] = IptcEntry(values, IptcText.describe(record, dataset, values), typed = true)
    }

    fun removeIptc(dataset: Int, record: Int = IptcTags.RECORD) {
        iptc.remove(IptcKey(record, dataset))
    }

    // ---- Blocks ----

    fun removeBlock(block: MetadataBlock) {
        removedBlocks += block
        when (block) {
            MetadataBlock.Exif -> exif.clear()
            MetadataBlock.ExifThumbnail -> exif.keys.removeAll { it.ifd == ExifIfd.Thumbnail }
            MetadataBlock.MakerNote -> exif.remove(MAKER_NOTE_KEY)
            MetadataBlock.Xmp -> xmp.clear()
            MetadataBlock.Iptc, MetadataBlock.PhotoshopResources -> iptc.clear()
            MetadataBlock.IccProfile, MetadataBlock.Comments, MetadataBlock.EmbeddedMedia -> Unit
        }
    }

    /**
     * Applies low-level [changes] in the writer's order (block removals first). With [onlyExisting]
     * values are only set where the field already exists; removals always apply.
     */
    fun apply(changes: MetadataChanges, onlyExisting: Boolean) {
        if (!onlyExisting) changes.removeBlocks.forEach(::removeBlock)
        for (change in changes.exif) when (change) {
            is ExifChange.Set -> if (!onlyExisting || hasExif(change.ifd, change.tag)) setExif(change.ifd, change.tag, change.value)
            is ExifChange.Remove -> removeExif(change.ifd, change.tag)
        }
        for (change in changes.xmp) {
            when (change) {
                is XmpChange.SetProperty -> setXmpChange(XmpName(change.namespace, change.name.substringAfter(':')), XmpShape.Simple, listOf(change.value), onlyExisting)
                is XmpChange.SetArray -> {
                    val shape = when (change.kind) {
                        XmpArrayKind.Seq -> XmpShape.Seq
                        XmpArrayKind.Bag -> XmpShape.Bag
                        XmpArrayKind.Alt -> XmpShape.Alt
                    }
                    setXmpChange(XmpName(change.namespace, change.name.substringAfter(':')), shape, change.values, onlyExisting)
                }
                is XmpChange.SetLocalizedText ->
                    setXmpChange(XmpName(change.namespace, change.name.substringAfter(':')), XmpShape.LangAlt, listOf(change.value), onlyExisting)
                is XmpChange.Remove -> removeXmp(XmpName(change.namespace, change.name.substringAfter(':')))
                is XmpChange.RemoveNamespace -> removeNamespace(change.namespace)
            }
        }
        for (change in changes.iptc) when (change) {
            is IptcChange.Set -> if (!onlyExisting || IptcKey(change.record, change.dataset) in iptc) setIptc(change.dataset, change.values, change.record)
            is IptcChange.Remove -> removeIptc(change.dataset, change.record)
        }
    }

    private fun setXmpChange(name: XmpName, shape: XmpShape, values: List<String>, onlyExisting: Boolean) {
        if (!onlyExisting || name in xmp) setXmp(name, shape, values)
    }

    // ---- Results ----

    private fun effectiveBlocks(): Set<MetadataBlock> = removedBlocks.filterTo(LinkedHashSet()) { it in presentBlocks || uncertain }

    private fun exifCleared(key: ExifKey): Boolean = MetadataBlock.Exif in removedBlocks || key.ifd in droppedIfds ||
        (key.ifd == ExifIfd.Thumbnail && MetadataBlock.ExifThumbnail in removedBlocks) ||
        (key == MAKER_NOTE_KEY && MetadataBlock.MakerNote in removedBlocks)

    private fun xmpBlockRemoved(): Boolean = MetadataBlock.Xmp in removedBlocks

    private fun xmpCleared(name: XmpName): Boolean = xmpBlockRemoved() || name.namespace in removedNamespaces

    private fun iptcCleared(): Boolean = MetadataBlock.Iptc in removedBlocks || MetadataBlock.PhotoshopResources in removedBlocks

    fun changes(): MetadataChanges {
        val exifChanges = mutableListOf<ExifChange>()
        if (MetadataBlock.Exif !in removedBlocks) {
            for (ifd in droppedIfds) {
                val hadIfd = originalExif.keys.any { it.ifd == ifd } || uncertain
                if (!hadIfd || (ifd == ExifIfd.Interoperability && ExifIfd.Exif in droppedIfds)) continue
                exifChanges += when (ifd) {
                    ExifIfd.Gps -> ExifChange.Remove(ExifIfd.Primary, ExifTags.GPS_IFD_POINTER)
                    ExifIfd.Exif -> ExifChange.Remove(ExifIfd.Primary, ExifTags.EXIF_IFD_POINTER)
                    else -> ExifChange.Remove(ExifIfd.Exif, ExifTags.INTEROP_IFD_POINTER)
                }
            }
        }
        for (key in originalExif.keys) if (key !in exif && !exifCleared(key)) exifChanges += ExifChange.Remove(key.ifd, key.tag)
        for ((key, entry) in exif) {
            val value = entry.value ?: continue
            val original = originalExif[key]
            if (exifCleared(key) || original == null || !original.sameAs(entry)) exifChanges += ExifChange.Set(key.ifd, key.tag, value)
        }

        val xmpChanges = mutableListOf<XmpChange>()
        if (!xmpBlockRemoved()) {
            for (namespace in removedNamespaces) {
                if (uncertain || originalXmp.keys.any { it.namespace == namespace }) xmpChanges += XmpChange.RemoveNamespace(namespace)
            }
        }
        for (name in originalXmp.keys) if (name !in xmp && !xmpCleared(name)) xmpChanges += XmpChange.Remove(name.namespace, name.name)
        for ((name, entry) in xmp) {
            if (!entry.typed) continue
            val original = originalXmp[name]
            if (xmpCleared(name) || original == null || !original.sameAs(entry)) xmpChanges += xmpChange(name, entry)
        }

        val iptcChanges = mutableListOf<IptcChange>()
        for (key in originalIptc.keys) if (key !in iptc && !iptcCleared()) iptcChanges += IptcChange.Remove(key.record, key.dataset)
        for ((key, entry) in iptc) {
            if (!entry.typed) continue
            val original = originalIptc[key]
            if (iptcCleared() || original == null || !original.sameAs(entry)) iptcChanges += IptcChange.Set(key.record, key.dataset, entry.values)
        }
        return MetadataChanges(exifChanges, xmpChanges, iptcChanges, effectiveBlocks())
    }

    private fun xmpChange(name: XmpName, entry: XmpEntry): XmpChange {
        val prefix = name.preferredPrefix
        return when (entry.shape) {
            XmpShape.Simple, XmpShape.Struct -> XmpChange.SetProperty(name.namespace, prefix, name.name, entry.values.firstOrNull().orEmpty())
            XmpShape.Seq -> XmpChange.SetArray(name.namespace, prefix, name.name, entry.values, XmpArrayKind.Seq)
            XmpShape.Bag -> XmpChange.SetArray(name.namespace, prefix, name.name, entry.values, XmpArrayKind.Bag)
            XmpShape.Alt -> XmpChange.SetArray(name.namespace, prefix, name.name, entry.values, XmpArrayKind.Alt)
            XmpShape.LangAlt -> XmpChange.SetLocalizedText(name.namespace, prefix, name.name, entry.values.firstOrNull().orEmpty())
        }
    }

    /**
     * One line per changed field. Whole-block removals become one line per block (plus the fields
     * that matter most, such as coordinates, names and serial numbers) instead of every tag.
     */
    fun diff(): List<FieldDiff> {
        val lines = mutableListOf<Pair<Int, FieldDiff>>()
        fun block(label: String, group: DirectoryGroup, before: String) {
            lines += group.ordinal * 2 to FieldDiff(label, group, before, null)
        }
        fun field(diff: FieldDiff) {
            lines += diff.group.ordinal * 2 + 1 to diff
        }
        val blocks = effectiveBlocks()
        val directories = report.directories
        val exifRemoved = MetadataBlock.Exif in blocks
        if (exifRemoved) {
            val count = directories.filter { it.group == DirectoryGroup.Exif || it.group == DirectoryGroup.Gps }.sumOf { it.tags.size }
            if (count > 0) block("EXIF data", DirectoryGroup.Exif, plural(count, "tag"))
        }
        val makerNotes = directories.filter { it.group == DirectoryGroup.MakerNote }
        if (exifRemoved || MetadataBlock.MakerNote in blocks) {
            when {
                makerNotes.isNotEmpty() ->
                    block("MakerNote", DirectoryGroup.MakerNote, "${makerNotes.first().name}, ${plural(makerNotes.sumOf { it.tags.size }, "tag")}")
                hasRawMakerNote() -> block("MakerNote", DirectoryGroup.MakerNote, "Vendor data")
            }
        }
        if ((exifRemoved || MetadataBlock.ExifThumbnail in blocks) && directories.any { it.id.startsWith("exif-thumbnail") }) {
            block("Thumbnail", DirectoryGroup.Exif, "Embedded preview image")
        }
        if (MetadataBlock.Exif !in removedBlocks) {
            for (ifd in droppedIfds) {
                val count = originalExif.keys.count { it.ifd == ifd }
                if (count == 0) continue
                val (label, group) = when (ifd) {
                    ExifIfd.Gps -> "GPS data" to DirectoryGroup.Gps
                    ExifIfd.Exif -> "Exif SubIFD" to DirectoryGroup.Exif
                    else -> "Interoperability data" to DirectoryGroup.Exif
                }
                block(label, group, plural(count, "tag"))
            }
        }
        if (MetadataBlock.Xmp in blocks) {
            val count = directories.filter { it.group == DirectoryGroup.Xmp }.sumOf { it.tags.size }
            if (count > 0) block("XMP packet", DirectoryGroup.Xmp, plural(count, "property", "properties"))
        }
        if ((MetadataBlock.Iptc in blocks || MetadataBlock.PhotoshopResources in blocks) && originalIptc.isNotEmpty()) {
            block("IPTC data", DirectoryGroup.Iptc, plural(originalIptc.size, "field"))
        }
        if (MetadataBlock.PhotoshopResources in blocks) {
            directories.firstOrNull { it.id == "photoshop" }?.let { block("Photoshop resources", DirectoryGroup.Other, plural(it.tags.size, "resource")) }
        }
        if (MetadataBlock.Comments in blocks) {
            val comments = directories.filter { isCommentDirectory(it.id) }.flatMap { it.tags }.map { it.displayValue }
            if (comments.isNotEmpty()) block("Comments", DirectoryGroup.Container, comments.joinToString("; ").take(MAX_DIFF_TEXT))
        }
        if (MetadataBlock.IccProfile in blocks && directories.any { it.group == DirectoryGroup.Icc }) {
            block("ICC profile", DirectoryGroup.Icc, report.summary.colorProfile ?: "Color profile")
        }

        val described = HashMap<ExifKey, String>()
        exif.entries.filter { it.value.value != null }.groupBy { it.key.ifd }.forEach { (ifd, entries) ->
            val texts = ExifText.describe(ifd, entries.associate { it.key.tag to it.value.value!! })
            for (entry in entries) described[entry.key] = texts.getValue(entry.key.tag)
        }
        for (key in LinkedHashSet(originalExif.keys + exif.keys)) {
            val before = originalExif[key]
            val after = exif[key]
            if (before == null && after == null) continue
            if (before != null && after != null && before.sameAs(after)) continue
            if (after == null && exifCleared(key) && !isImportant(key)) continue
            val label = exifTags[key]?.name ?: ExifText.tagName(key.ifd, key.tag)
            val group = if (key.ifd == ExifIfd.Gps) DirectoryGroup.Gps else DirectoryGroup.Exif
            field(FieldDiff(label, group, before?.display, after?.let { described[key] ?: it.display }))
        }
        for (name in LinkedHashSet(originalXmp.keys + xmp.keys)) {
            val before = originalXmp[name]
            val after = xmp[name]
            if (before == null && after == null) continue
            if (before != null && after != null && before.sameAs(after)) continue
            if (after == null && xmpBlockRemoved() && !isImportant(name)) continue
            val prefix = (after ?: before)!!.prefix
            field(FieldDiff("$prefix:${name.name}", DirectoryGroup.Xmp, before?.display, after?.display))
        }
        for (key in LinkedHashSet(originalIptc.keys + iptc.keys)) {
            val before = originalIptc[key]
            val after = iptc[key]
            if (before == null && after == null) continue
            if (before != null && after != null && before.sameAs(after)) continue
            if (after == null && iptcCleared() && iptcTags[key]?.sensitivity == null) continue
            val label = iptcTags[key]?.name ?: IptcText.tagName(key.record, key.dataset)
            field(FieldDiff(label, DirectoryGroup.Iptc, before?.display, after?.display))
        }
        return lines.withIndex().sortedWith(compareBy({ it.value.first }, { it.index })).map { it.value.second }
    }

    /** A MakerNote tag the reader could not decode into a vendor directory. */
    private fun hasRawMakerNote(): Boolean = MAKER_NOTE_KEY in originalExif

    private fun isImportant(key: ExifKey): Boolean =
        key in IMPORTANT_EXIF || (key.ifd != ExifIfd.Gps && exifTags[key]?.sensitivity != null)

    private fun isImportant(name: XmpName): Boolean = when {
        name.namespace == XmpNs.EXIF && name.name.startsWith("GPS") -> name.name == "GPSLatitude" || name.name == "GPSLongitude"
        else -> name in xmpSensitive
    }

    // ---- Reading the report ----

    private fun readExif(): Map<ExifKey, ExifEntry> {
        val map = LinkedHashMap<ExifKey, ExifEntry>()
        for ((id, ifd) in EXIF_DIRECTORIES) {
            val directory = report.directories.firstOrNull { it.id == id } ?: continue
            for (tag in directory.tags) {
                val key = ExifKey(ifd, tag.id ?: continue)
                if (key in map) continue
                map[key] = ExifEntry(tag.rawValue, tag.displayValue, null)
                exifTags[key] = tag
            }
        }
        return map
    }

    private fun readXmp(): Map<XmpName, XmpEntry> {
        class Builder(val prefix: String) {
            var simple: String? = null
            val items = mutableListOf<String>()
            val leaves = mutableListOf<String>()
        }
        val registry = XMPMetaFactory.getSchemaRegistry()
        val builders = LinkedHashMap<XmpName, Builder>()
        for (directory in report.directories.filter { it.group == DirectoryGroup.Xmp }) {
            val earlier = builders.keys.toSet()
            for (tag in directory.tags) {
                val path = tag.name
                val root = path.substringBefore('/').substringBefore('[')
                val prefix = root.substringBefore(':', "")
                if (prefix.isEmpty()) continue
                val namespace = registry.getNamespaceURI(prefix) ?: continue
                val name = XmpName(namespace, root.substringAfter(':'))
                if (name in earlier) continue
                val builder = builders.getOrPut(name) { Builder(prefix) }
                val rest = path.substring(root.length)
                when {
                    rest.isEmpty() -> builder.simple = tag.rawValue
                    ITEM.matches(rest) -> builder.items += tag.rawValue
                    else -> builder.leaves += "${rest.trimStart('/')}=${tag.rawValue}"
                }
                if (tag.sensitivity != null) xmpSensitive += name
            }
        }
        return builders.mapValuesTo(LinkedHashMap()) { (_, b) ->
            when {
                b.leaves.isNotEmpty() -> XmpEntry(b.prefix, XmpShape.Struct, b.leaves + b.items + listOfNotNull(b.simple), typed = false)
                b.items.isNotEmpty() -> XmpEntry(b.prefix, XmpShape.Seq, b.items, typed = false)
                else -> XmpEntry(b.prefix, XmpShape.Simple, listOf(b.simple.orEmpty()), typed = false)
            }
        }
    }

    private fun readIptc(): Map<IptcKey, IptcEntry> {
        val map = LinkedHashMap<IptcKey, IptcEntry>()
        for (directory in report.directories.filter { it.group == DirectoryGroup.Iptc }) {
            for (tag in directory.tags) {
                val id = tag.id ?: continue
                val key = IptcKey(id shr 8, id and 0xFF)
                if (key in map) continue
                // Repeated datasets come joined; keywords are joined with ";" so they can be split again.
                val values = if (key.record == IptcTags.RECORD && key.dataset == IptcTags.KEYWORDS) {
                    tag.displayValue.split(';').map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    listOf(tag.rawValue)
                }
                map[key] = IptcEntry(values, tag.displayValue, typed = false)
                iptcTags[key] = tag
            }
        }
        return map
    }

    private fun prefixOf(name: XmpName): String =
        originalXmp[name]?.prefix
            ?: XMPMetaFactory.getSchemaRegistry().getNamespacePrefix(name.namespace)?.removeSuffix(":")
            ?: name.preferredPrefix

    private companion object {
        val EXIF_DIRECTORIES = listOf(
            "exif-ifd0" to ExifIfd.Primary,
            "exif-subifd" to ExifIfd.Exif,
            "gps" to ExifIfd.Gps,
            "exif-interop" to ExifIfd.Interoperability,
            "exif-thumbnail" to ExifIfd.Thumbnail,
        )
        val ITEM = Regex("""^\[\d+]$""")
        val MAKER_NOTE_KEY = ExifKey(ExifIfd.Exif, ExifTags.MAKER_NOTE)
        const val MAX_DIFF_TEXT = 200

        /** Fields listed individually even when their whole block is removed. */
        val IMPORTANT_EXIF = setOf(
            ExifKey(ExifIfd.Primary, ExifTags.MAKE),
            ExifKey(ExifIfd.Primary, ExifTags.MODEL),
            ExifKey(ExifIfd.Exif, ExifTags.DATE_TIME_ORIGINAL),
            ExifKey(ExifIfd.Gps, ExifTags.GPS_LATITUDE),
            ExifKey(ExifIfd.Gps, ExifTags.GPS_LONGITUDE),
            ExifKey(ExifIfd.Gps, ExifTags.GPS_ALTITUDE),
        )

        fun isCommentDirectory(id: String): Boolean =
            id.startsWith("jpeg-comment") || id.startsWith("png-text") || id.startsWith("png-ztxt") || id.startsWith("png-itxt")
    }
}

/** "1 tag", "3 tags". */
internal fun plural(count: Int, singular: String, pluralForm: String = singular + "s"): String =
    "$count ${if (count == 1) singular else pluralForm}"
