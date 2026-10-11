package io.github.fishpimp.exiflab.metadata.write.heif

import io.github.fishpimp.exiflab.metadata.write.UnsupportedEditException

/** One `infe` entry. [raw] holds the original box, which is written back unchanged. */
internal class ItemInfo(
    val id: Long,
    val type: String,
    val contentType: String,
    val protectionIndex: Int,
    private val raw: ByteArray?,
    /** `content_encoding` of MIME items, e.g. "deflate" for compressed XMP; empty when stored as is. */
    val contentEncoding: String = "",
) {
    val isExif: Boolean get() = type == EXIF_TYPE
    val isXmp: Boolean get() = type == MIME_TYPE && contentType.substringBefore(';').trim().equals(XMP_CONTENT_TYPE, ignoreCase = true)

    /** Exif and XMP items are metadata; every other item is image data (or describes it). */
    val isMetadata: Boolean get() = isExif || isXmp

    fun bytes(): ByteArray = raw ?: run {
        // A new item: version 2 (16-bit ids) or 3, flagged hidden as libheif and Apple do for metadata.
        val writer = ByteWriter()
        if (id > 0xFFFF) writer.u32(id) else writer.u16(id.toInt())
        writer.u16(0)
        writer.fourCc(type)
        writer.cString("")
        if (type == MIME_TYPE) writer.cString(contentType)
        BoxBytes.fullBox("infe", if (id > 0xFFFF) 3 else 2, 1, writer.toByteArray())
    }

    companion object {
        const val EXIF_TYPE = "Exif"
        const val MIME_TYPE = "mime"
        const val XMP_CONTENT_TYPE = "application/rdf+xml"

        fun parse(data: ByteArray, box: MemBox): ItemInfo {
            val reader = ByteReader(data, box.payloadStart, box.end, "infe")
            val version = reader.u8()
            reader.u8()
            reader.u16()
            return when (version) {
                0, 1 -> {
                    // Versions 0 and 1 describe MIME-typed items: name, content type, optional encoding.
                    val id = reader.u16().toLong()
                    val protection = reader.u16()
                    reader.cString()
                    val contentType = if (reader.remaining > 0) reader.cString() else ""
                    val encoding = if (reader.remaining > 0) reader.cString() else ""
                    ItemInfo(id, MIME_TYPE, contentType, protection, data.copyOfRange(box.start, box.end), encoding)
                }
                2, 3 -> {
                    val id = if (version == 2) reader.u16().toLong() else reader.u32()
                    val protection = reader.u16()
                    val type = reader.fourCc()
                    reader.cString()
                    val contentType = if (type == MIME_TYPE && reader.remaining > 0) reader.cString() else ""
                    val encoding = if (type == MIME_TYPE && reader.remaining > 0) reader.cString() else ""
                    ItemInfo(id, type, contentType, protection, data.copyOfRange(box.start, box.end), encoding)
                }
                else -> BoxBytes.damaged("infe")
            }
        }
    }
}

/** One extent of an `iloc` entry. [length] 0 means "to the end of the file (or idat)" until resolved. */
internal class Extent(val index: Long, var offset: Long, var length: Long)

/** One `iloc` entry. */
internal class ItemLocation(
    val id: Long,
    var constructionMethod: Int,
    val dataReferenceIndex: Int,
    var baseOffset: Long,
    val extents: MutableList<Extent>,
)

/** The `iloc` box with every field size and version it supports (0-2; offsets, lengths, bases and indexes of 0, 4 or 8 bytes). */
internal class ItemLocations(
    var version: Int,
    var offsetSize: Int,
    var lengthSize: Int,
    var baseOffsetSize: Int,
    var indexSize: Int,
    val entries: MutableList<ItemLocation>,
) {
    operator fun get(id: Long): ItemLocation? = entries.firstOrNull { it.id == id }

    /**
     * Serializes the entries, widening field sizes (and the version for 32-bit item ids) where a
     * value no longer fits. Sizes never shrink, so repeated layout passes converge.
     */
    fun bytes(): ByteArray {
        fun sizeFor(current: Int, values: Sequence<Long>): Int {
            val max = values.maxOrNull() ?: 0L
            return when {
                max > 0xFFFFFFFFL -> 8
                max > 0L && current == 0 -> 4
                else -> current
            }
        }
        offsetSize = sizeFor(offsetSize, entries.asSequence().flatMap { it.extents }.map { it.offset })
        lengthSize = sizeFor(lengthSize, entries.asSequence().flatMap { it.extents }.map { it.length })
        baseOffsetSize = sizeFor(baseOffsetSize, entries.asSequence().map { it.baseOffset })
        if (entries.any { it.id > 0xFFFF } || entries.size > 0xFFFF) version = 2
        if (version == 0 && entries.any { it.constructionMethod != 0 }) version = 1
        if (version >= 1) indexSize = sizeFor(indexSize, entries.asSequence().flatMap { it.extents }.map { it.index })

        val writer = ByteWriter()
        writer.u8((offsetSize shl 4) or lengthSize)
        writer.u8((baseOffsetSize shl 4) or if (version >= 1) indexSize else 0)
        if (version < 2) writer.u16(entries.size) else writer.u32(entries.size.toLong())
        for (entry in entries) {
            if (version < 2) writer.u16(entry.id.toInt()) else writer.u32(entry.id)
            if (version >= 1) writer.u16(entry.constructionMethod)
            writer.u16(entry.dataReferenceIndex)
            writer.uint(entry.baseOffset, baseOffsetSize)
            writer.u16(entry.extents.size)
            for (extent in entry.extents) {
                if (version >= 1 && indexSize > 0) writer.uint(extent.index, indexSize)
                writer.uint(extent.offset, offsetSize)
                writer.uint(extent.length, lengthSize)
            }
        }
        return BoxBytes.fullBox("iloc", version, 0, writer.toByteArray())
    }

    companion object {
        private val FIELD_SIZES = setOf(0, 4, 8)

        fun parse(data: ByteArray, box: MemBox): ItemLocations {
            val reader = ByteReader(data, box.payloadStart, box.end, "iloc")
            val version = reader.u8()
            reader.u8()
            reader.u16()
            if (version > 2) BoxBytes.damaged("iloc")
            val sizes = reader.u8()
            val sizes2 = reader.u8()
            val offsetSize = sizes shr 4
            val lengthSize = sizes and 0x0F
            val baseOffsetSize = sizes2 shr 4
            val indexSize = if (version >= 1) sizes2 and 0x0F else 0
            if (listOf(offsetSize, lengthSize, baseOffsetSize, indexSize).any { it !in FIELD_SIZES }) BoxBytes.damaged("iloc")
            val count = if (version < 2) reader.u16().toLong() else reader.u32()
            val entries = mutableListOf<ItemLocation>()
            for (i in 0 until count) {
                val id = if (version < 2) reader.u16().toLong() else reader.u32()
                val method = if (version >= 1) reader.u16() and 0x0F else 0
                val dataReference = reader.u16()
                val base = reader.uint(baseOffsetSize)
                val extentCount = reader.u16()
                val extents = MutableList(extentCount) {
                    val index = if (version >= 1 && indexSize > 0) reader.uint(indexSize) else 0L
                    Extent(index, reader.uint(offsetSize), reader.uint(lengthSize))
                }
                entries += ItemLocation(id, method, dataReference, base, extents)
            }
            if (reader.remaining != 0) BoxBytes.damaged("iloc")
            if (entries.map { it.id }.toSet().size != entries.size) throw UnsupportedEditException("The HEIF file locates an item twice and cannot be rewritten safely")
            return ItemLocations(version, offsetSize, lengthSize, baseOffsetSize, indexSize, entries)
        }
    }
}

/** One reference of an `iref` box, e.g. `cdsc` from an Exif item to the image it describes. */
internal class ItemReference(val type: String, val from: Long, val to: MutableList<Long>)

internal class ItemReferences(var version: Int, val references: MutableList<ItemReference>) {
    fun bytes(): ByteArray {
        if (references.any { reference -> reference.from > 0xFFFF || reference.to.any { it > 0xFFFF } }) version = 1
        val writer = ByteWriter()
        for (reference in references) {
            val body = ByteWriter()
            if (version == 0) body.u16(reference.from.toInt()) else body.u32(reference.from)
            body.u16(reference.to.size)
            for (id in reference.to) if (version == 0) body.u16(id.toInt()) else body.u32(id)
            writer.bytes(BoxBytes.box(reference.type, body.toByteArray()))
        }
        return BoxBytes.fullBox("iref", version, 0, writer.toByteArray())
    }

    companion object {
        fun parse(data: ByteArray, box: MemBox): ItemReferences {
            val version = BoxBytes.u8(data, box.payloadStart)
            if (version > 1 || box.end - box.payloadStart < 4) BoxBytes.damaged("iref")
            val references = BoxBytes.children(data, box.payloadStart + 4, box.end, "iref").map { child ->
                val reader = ByteReader(data, child.payloadStart, child.end, "iref")
                val from = if (version == 0) reader.u16().toLong() else reader.u32()
                val count = reader.u16()
                val to = MutableList(count) { if (version == 0) reader.u16().toLong() else reader.u32() }
                if (reader.remaining != 0) BoxBytes.damaged("iref")
                ItemReference(child.type, from, to)
            }
            return ItemReferences(version, references.toMutableList())
        }
    }
}

/** Property associations of one item: (essential, 1-based index into `ipco`, 0 for none). */
internal class Association(val essential: Boolean, val index: Int)

internal class PropertyAssociations(val version: Int, val flags: Int, val entries: MutableList<Pair<Long, List<Association>>>) {
    fun bytes(): ByteArray {
        val writer = ByteWriter()
        writer.u32(entries.size.toLong())
        for ((id, associations) in entries) {
            if (version < 1) writer.u16(id.toInt()) else writer.u32(id)
            writer.u8(associations.size)
            for (association in associations) {
                val essential = if (association.essential) 1 else 0
                if (flags and 1 != 0) writer.u16((essential shl 15) or association.index) else writer.u8((essential shl 7) or association.index)
            }
        }
        return BoxBytes.fullBox("ipma", version, flags, writer.toByteArray())
    }

    companion object {
        fun parse(data: ByteArray, box: MemBox): PropertyAssociations {
            val reader = ByteReader(data, box.payloadStart, box.end, "ipma")
            val version = reader.u8()
            val flags = (reader.u8() shl 16) or reader.u16()
            if (version > 1) BoxBytes.damaged("ipma")
            val count = reader.u32()
            val entries = mutableListOf<Pair<Long, List<Association>>>()
            for (i in 0 until count) {
                val id = if (version < 1) reader.u16().toLong() else reader.u32()
                val associations = List(reader.u8()) {
                    if (flags and 1 != 0) {
                        val value = reader.u16()
                        Association(value and 0x8000 != 0, value and 0x7FFF)
                    } else {
                        val value = reader.u8()
                        Association(value and 0x80 != 0, value and 0x7F)
                    }
                }
                entries += id to associations
            }
            if (reader.remaining != 0) BoxBytes.damaged("ipma")
            return PropertyAssociations(version, flags, entries)
        }
    }
}

/**
 * The `meta` box of a HEIF/AVIF file as the writer edits it: item infos, locations, references,
 * property associations and `idat`. Every other child box (and every unchanged part) is written
 * back byte for byte; [bytes] returns the original box when nothing changed.
 */
internal class HeifMeta private constructor(
    private val original: ByteArray,
    private val fullBoxHeader: ByteArray,
    private val children: List<Pair<String, ByteArray>>,
    val handlerType: String,
    val primaryId: Long?,
    val infos: MutableList<ItemInfo>,
    private val infoVersion: Int,
    val locations: ItemLocations,
    var references: ItemReferences?,
    /** Raw `ipco` property boxes in order (property index 1 is the first). */
    val properties: List<ByteArray>,
    private val iprpChildren: List<Pair<String, ByteArray>>,
    val associations: List<PropertyAssociations>,
    idat: ByteArray?,
    /** Raw `grpl` box, if any, and every id it mentions (group ids share the item id space). */
    val groups: ByteArray?,
    private val groupIds: Set<Long>,
) {
    var idat: ByteArray? = idat
        set(value) {
            field = value
            dirty = true
        }

    private var dirty = false

    fun markDirty() {
        dirty = true
    }

    fun info(id: Long): ItemInfo? = infos.firstOrNull { it.id == id }

    /** Item ids in use anywhere in the box; a new item takes the next free one. */
    fun usedIds(): Set<Long> = buildSet {
        infos.mapTo(this) { it.id }
        locations.entries.mapTo(this) { it.id }
        references?.references?.forEach { reference ->
            add(reference.from)
            addAll(reference.to)
        }
        associations.forEach { box -> box.entries.mapTo(this) { it.first } }
        primaryId?.let(::add)
        addAll(groupIds)
    }

    /** The items that [id] describes through `cdsc`. */
    fun describes(id: Long): List<Long> = references?.references.orEmpty().filter { it.type == "cdsc" && it.from == id }.flatMap { it.to }

    /** Drops [id] from `iinf`, `iloc`, `iref` and `ipma`, leaving no reference to it behind. */
    fun removeItem(id: Long) {
        if (id == primaryId) throw UnsupportedEditException("The primary image item cannot be removed")
        if (id in groupIds) throw UnsupportedEditException("The HEIF item $id belongs to an entity group and cannot be removed safely")
        infos.removeAll { it.id == id }
        locations.entries.removeAll { it.id == id }
        references?.let { refs ->
            refs.references.removeAll { it.from == id }
            refs.references.forEach { reference -> reference.to.removeAll { it == id } }
            refs.references.removeAll { it.to.isEmpty() }
        }
        for (box in associations) box.entries.removeAll { it.first == id }
        dirty = true
    }

    /** Adds a new item described by [info] and located by [location], linked to [describes] with `cdsc`. */
    fun addItem(info: ItemInfo, location: ItemLocation, describes: Long) {
        infos += info
        locations.entries += location
        val refs = references ?: ItemReferences(0, mutableListOf()).also { references = it }
        refs.references += ItemReference("cdsc", info.id, mutableListOf(describes))
        dirty = true
    }

    fun bytes(): ByteArray {
        if (!dirty) return original
        val body = ByteWriter()
        body.bytes(fullBoxHeader)
        var wroteReferences = false
        for ((type, raw) in children) {
            when (type) {
                "iinf" -> {
                    body.bytes(infoBytes())
                    if (children.none { it.first == "iref" }) {
                        references?.takeIf { it.references.isNotEmpty() }?.let { body.bytes(it.bytes()) }
                        wroteReferences = true
                    }
                }
                "iloc" -> body.bytes(locations.bytes())
                "iref" -> if (!wroteReferences) {
                    references?.takeIf { it.references.isNotEmpty() }?.let { body.bytes(it.bytes()) }
                    wroteReferences = true
                }
                "iprp" -> body.bytes(propertiesBytes())
                "idat" -> body.bytes(BoxBytes.box("idat", idat ?: ByteArray(0)))
                else -> body.bytes(raw)
            }
        }
        return BoxBytes.box("meta", body.toByteArray())
    }

    private fun infoBytes(): ByteArray {
        val writer = ByteWriter()
        val version = if (infoVersion == 0 && infos.size > 0xFFFF) 1 else infoVersion
        if (version == 0) writer.u16(infos.size) else writer.u32(infos.size.toLong())
        for (info in infos) writer.bytes(info.bytes())
        return BoxBytes.fullBox("iinf", version, 0, writer.toByteArray())
    }

    private fun propertiesBytes(): ByteArray {
        val writer = ByteWriter()
        var next = 0
        for ((type, raw) in iprpChildren) {
            writer.bytes(if (type == "ipma") associations[next++].bytes() else raw)
        }
        return BoxBytes.box("iprp", writer.toByteArray())
    }

    companion object {
        private val SINGLE_CHILDREN = setOf("hdlr", "pitm", "iinf", "iloc", "iref", "iprp", "idat", "grpl", "dinf")

        /** Parses a whole `meta` box ([data] starts with its header). */
        fun parse(data: ByteArray): HeifMeta {
            val top = BoxBytes.children(data, 0, data.size, "meta").singleOrNull()?.takeIf { it.type == "meta" } ?: BoxBytes.damaged("meta")
            // `meta` is a FullBox in HEIF; QuickTime-style boxes without version and flags start with `hdlr` right away.
            val quickTime = top.end - top.payloadStart >= 8 && BoxBytes.fourCc(data, top.payloadStart + 4) == "hdlr"
            if (quickTime || top.end - top.payloadStart < 4 || BoxBytes.u8(data, top.payloadStart) != 0) {
                throw UnsupportedEditException("This HEIF meta box layout is not supported")
            }
            val children = BoxBytes.children(data, top.payloadStart + 4, top.end, "meta")
            for (type in SINGLE_CHILDREN) if (children.count { it.type == type } > 1) throw UnsupportedEditException("The HEIF file has several '$type' boxes and cannot be rewritten safely")
            val byType = children.associateBy { it.type }
            fun raw(box: MemBox) = data.copyOfRange(box.start, box.end)

            val hdlr = byType["hdlr"] ?: throw UnsupportedEditException("The HEIF meta box has no handler")
            if (hdlr.end - hdlr.payloadStart < 12) BoxBytes.damaged("hdlr")
            val handlerType = BoxBytes.fourCc(data, hdlr.payloadStart + 8)

            val primaryId = byType["pitm"]?.let { box ->
                val reader = ByteReader(data, box.payloadStart, box.end, "pitm")
                val version = reader.u8()
                reader.u8()
                reader.u16()
                if (version == 0) reader.u16().toLong() else reader.u32()
            }

            val iinf = byType["iinf"] ?: throw UnsupportedEditException("The HEIF meta box has no item list")
            val infoVersion = BoxBytes.u8(data, iinf.payloadStart)
            val infoHeader = if (infoVersion == 0) 6 else 8
            if (iinf.end - iinf.payloadStart < infoHeader) BoxBytes.damaged("iinf")
            val declaredCount = if (infoVersion == 0) BoxBytes.u16(data, iinf.payloadStart + 4).toLong() else BoxBytes.u32(data, iinf.payloadStart + 4)
            val infeBoxes = BoxBytes.children(data, iinf.payloadStart + infoHeader, iinf.end, "iinf")
            if (infeBoxes.any { it.type != "infe" } || infeBoxes.size.toLong() != declaredCount) BoxBytes.damaged("iinf")
            val infos = infeBoxes.map { ItemInfo.parse(data, it) }.toMutableList()
            if (infos.map { it.id }.toSet().size != infos.size) throw UnsupportedEditException("The HEIF file describes an item twice and cannot be rewritten safely")

            val iloc = byType["iloc"] ?: throw UnsupportedEditException("The HEIF meta box has no item locations")
            val locations = ItemLocations.parse(data, iloc)
            val references = byType["iref"]?.let { ItemReferences.parse(data, it) }

            val iprp = byType["iprp"]
            val iprpChildren = iprp?.let { BoxBytes.children(data, it.payloadStart, it.end, "iprp") }.orEmpty()
            if (iprpChildren.count { it.type == "ipco" } > 1) throw UnsupportedEditException("The HEIF file has several property containers")
            val properties = iprpChildren.firstOrNull { it.type == "ipco" }
                ?.let { ipco -> BoxBytes.children(data, ipco.payloadStart, ipco.end, "ipco").map(::raw) }.orEmpty()
            val associations = iprpChildren.filter { it.type == "ipma" }.map { PropertyAssociations.parse(data, it) }

            val grpl = byType["grpl"]
            val groupIds = grpl?.let { box ->
                BoxBytes.children(data, box.payloadStart, box.end, "grpl").flatMap { group ->
                    val reader = ByteReader(data, group.payloadStart, group.end, "grpl")
                    reader.u32()
                    val groupId = reader.u32()
                    val count = reader.u32()
                    if (count > reader.remaining / 4) BoxBytes.damaged("grpl")
                    listOf(groupId) + List(count.toInt()) { reader.u32() }
                }.toSet()
            }.orEmpty()

            return HeifMeta(
                original = data,
                fullBoxHeader = data.copyOfRange(top.payloadStart, top.payloadStart + 4),
                children = children.map { it.type to raw(it) },
                handlerType = handlerType,
                primaryId = primaryId,
                infos = infos,
                infoVersion = infoVersion,
                locations = locations,
                references = references,
                properties = properties,
                iprpChildren = iprpChildren.map { it.type to raw(it) },
                associations = associations,
                idat = byType["idat"]?.let { data.copyOfRange(it.payloadStart, it.end) },
                groups = grpl?.let(::raw),
                groupIds = groupIds,
            )
        }
    }
}
