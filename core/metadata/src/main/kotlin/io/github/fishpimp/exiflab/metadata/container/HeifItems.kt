package io.github.fishpimp.exiflab.metadata.container

import io.github.fishpimp.exiflab.metadata.io.ByteParser
import io.github.fishpimp.exiflab.metadata.io.SeekableSource
import io.github.fishpimp.exiflab.metadata.io.latin1
import java.io.ByteArrayOutputStream
import java.io.EOFException

/**
 * The item structure of a HEIF/AVIF `meta` box: which items exist (`iinf`), where their data
 * lives (`iloc`, `idat`), which item is the primary image (`pitm`) and its properties (`iprp`).
 */
internal class HeifItems private constructor(
    private val primaryItemId: Long?,
    private val infos: List<ItemInfo>,
    private val locations: Map<Long, ItemLocation>,
    private val properties: List<Property>,
    private val associations: Map<Long, List<Int>>,
    private val idat: BmffBox?,
) {
    class ItemInfo(val id: Long, val type: String, val contentType: String)

    private class Extent(val offset: Long, val length: Long)
    private class ItemLocation(val constructionMethod: Int, val baseOffset: Long, val extents: List<Extent>)
    private class Property(val type: String, val payload: ByteArray)

    /** Width and height from the `ispe` property of the primary item. */
    val primarySize: Pair<Long, Long>?
        get() {
            val id = primaryItemId ?: return null
            val ispe = associations[id].orEmpty()
                .mapNotNull { properties.getOrNull(it - 1) }
                .firstOrNull { it.type == "ispe" } ?: return null
            return try {
                ByteParser(ispe.payload, position = 4).let { it.u32() to it.u32() }
            } catch (_: EOFException) {
                null
            }
        }

    /** Items of the given HEIF item [type], e.g. "Exif" or "mime". */
    fun itemsOfType(type: String): List<ItemInfo> = infos.filter { it.type == type }

    /** Concatenated extents of [item], or null when its location is unknown or unsupported. */
    fun data(source: SeekableSource, item: ItemInfo): ByteArray? {
        val location = locations[item.id] ?: return null
        val base = when (location.constructionMethod) {
            0 -> location.baseOffset
            1 -> (idat ?: return null).payloadStart + location.baseOffset
            else -> return null
        }
        val out = ByteArrayOutputStream()
        for (extent in location.extents) {
            val start = base + extent.offset
            val length = if (extent.length == 0L) source.length - start else extent.length
            if (start < 0 || length <= 0 || out.size() + length > MAX_ITEM_SIZE) return null
            val bytes = source.readUpTo(start, length.toInt())
            out.write(bytes)
            if (bytes.size < length) break
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_BOX_SIZE = 4 * 1024 * 1024
        private const val MAX_ITEM_SIZE = 16L * 1024 * 1024

        /** Parses [meta]; structures that are missing or malformed are left out. */
        fun parse(source: SeekableSource, meta: BmffBox): HeifItems {
            // `meta` is a FullBox in HEIF; QuickTime-style files omit the version and flags.
            val probe = source.readUpTo(meta.payloadStart, 12)
            val skip = if (probe.size >= 12 && probe.latin1(4, 4) == "hdlr") 0 else 4
            val children = Bmff.children(source, meta, skip).associateBy { it.type }
            fun payload(box: BmffBox?): ByteArray? =
                box?.takeIf { it.payloadSize in 1..MAX_BOX_SIZE.toLong() }?.let { source.readUpTo(it.payloadStart, it.payloadSize.toInt()) }

            val iprp = children["iprp"]?.let { box -> Bmff.children(source, box).associateBy { it.type } }.orEmpty()
            return HeifItems(
                primaryItemId = payload(children["pitm"])?.let(::parsePrimaryItem),
                infos = children["iinf"]?.let { parseItemInfos(source, it) }.orEmpty(),
                locations = payload(children["iloc"])?.let(::parseLocations).orEmpty(),
                properties = iprp["ipco"]?.let { ipco ->
                    Bmff.children(source, ipco).map { Property(it.type, payload(it) ?: ByteArray(0)) }
                }.orEmpty(),
                associations = payload(iprp["ipma"])?.let(::parseAssociations).orEmpty(),
                idat = children["idat"],
            )
        }

        private fun parsePrimaryItem(bytes: ByteArray): Long? = guarded {
            val parser = ByteParser(bytes)
            val version = parser.u8()
            parser.position = 4
            if (version == 0) parser.u16().toLong() else parser.u32()
        }

        private fun parseItemInfos(source: SeekableSource, iinf: BmffBox): List<ItemInfo> {
            val header = source.readUpTo(iinf.payloadStart, 4)
            if (header.size < 4) return emptyList()
            // FullBox header, then a 16-bit (version 0) or 32-bit entry count before the `infe` boxes.
            val skip = if (header[0].toInt() == 0) 6 else 8
            return Bmff.children(source, iinf, skip).filter { it.type == "infe" && it.payloadSize in 1..64 * 1024L }
                .mapNotNull { infe -> parseItemInfo(source.readUpTo(infe.payloadStart, infe.payloadSize.toInt())) }
        }

        private fun parseItemInfo(bytes: ByteArray): ItemInfo? = guarded {
            val parser = ByteParser(bytes)
            val version = parser.u8()
            parser.position = 4
            if (version < 2) {
                val id = parser.u16().toLong()
                parser.u16()
                parser.cString()
                ItemInfo(id, "mime", parser.cString())
            } else {
                val id = if (version == 2) parser.u16().toLong() else parser.u32()
                parser.u16()
                val type = parser.fourCc()
                parser.cString()
                ItemInfo(id, type, if (type == "mime" && parser.remaining > 0) parser.cString() else "")
            }
        }

        private fun parseLocations(bytes: ByteArray): Map<Long, ItemLocation> = guarded {
            val parser = ByteParser(bytes)
            val version = parser.u8()
            parser.position = 4
            val sizes = parser.u8()
            val offsetSize = sizes shr 4
            val lengthSize = sizes and 0x0F
            val sizes2 = parser.u8()
            val baseOffsetSize = sizes2 shr 4
            val indexSize = if (version == 1 || version == 2) sizes2 and 0x0F else 0
            val itemCount = if (version < 2) parser.u16().toLong() else parser.u32()
            val result = HashMap<Long, ItemLocation>()
            for (i in 0 until itemCount) {
                val id = if (version < 2) parser.u16().toLong() else parser.u32()
                val method = if (version == 1 || version == 2) parser.u16() and 0x0F else 0
                parser.u16() // data_reference_index
                val baseOffset = parser.uint(baseOffsetSize)
                val extents = (0 until parser.u16()).map {
                    if (indexSize > 0) parser.uint(indexSize)
                    Extent(parser.uint(offsetSize), parser.uint(lengthSize))
                }
                result[id] = ItemLocation(method, baseOffset, extents)
            }
            result
        } ?: emptyMap()

        private fun parseAssociations(bytes: ByteArray): Map<Long, List<Int>> = guarded {
            val parser = ByteParser(bytes)
            val version = parser.u8()
            val flags = parser.uint(3)
            val result = HashMap<Long, List<Int>>()
            for (i in 0 until parser.u32()) {
                val id = if (version < 1) parser.u16().toLong() else parser.u32()
                result[id] = (0 until parser.u8()).map {
                    if (flags and 1L != 0L) parser.u16() and 0x7FFF else parser.u8() and 0x7F
                }
            }
            result
        } ?: emptyMap()

        private inline fun <T> guarded(block: () -> T): T? = try {
            block()
        } catch (_: EOFException) {
            null
        }
    }
}
