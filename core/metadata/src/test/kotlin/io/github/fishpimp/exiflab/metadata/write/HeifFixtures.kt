package io.github.fishpimp.exiflab.metadata.write

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.fixtures.Photos
import io.github.fishpimp.exiflab.metadata.fixtures.TiffBuilder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Synthetic HEIF/AVIF files for the writer tests, and an independent parser to check what the
 * writer produced (it shares no code with the writer's own box model).
 */
object HeifFixtures {
    class Item(
        val id: Int,
        val type: String,
        val data: ByteArray,
        val contentType: String? = null,
        val inIdat: Boolean = false,
        /** Number of extents the data is split into. */
        val extents: Int = 1,
        val constructionMethod: Int? = null,
        /** Points this item's single extent at the data of another item (AVIF tiles may share data). */
        val sameDataAs: Int? = null,
    )

    class Ref(val type: String, val from: Int, val to: List<Int>)

    /** An image sequence track whose samples live in `mdat` after the items. */
    class Track(val samples: List<ByteArray>, val samplesPerChunk: Int = 2, val wide: Boolean = false)

    data class Spec(
        val items: List<Item>,
        val primary: Int,
        val refs: List<Ref> = emptyList(),
        val properties: List<ByteArray> = emptyList(),
        /** Item id to 1-based property indexes; a negative index marks the association essential. */
        val associations: Map<Int, List<Int>> = emptyMap(),
        val brand: String = "heic",
        val ilocVersion: Int = 1,
        val offsetSize: Int = 4,
        val lengthSize: Int = 4,
        val baseOffsetSize: Int = 0,
        val indexSize: Int = 0,
        /** base_offset = start of the `mdat` payload, extent offsets relative to it (needs [baseOffsetSize]). */
        val baseRelative: Boolean = false,
        val metaAfterMdat: Boolean = false,
        val freeAfterMeta: Int = 0,
        val track: Track? = null,
        /** Extra boxes appended inside `meta` (e.g. `grpl`). */
        val extraMeta: List<ByteArray> = emptyList(),
        val dataReference: Int = 0,
        /** Items in `mdat` in this order (by id); default: as listed. */
        val dataOrder: List<Int>? = null,
        /** Each item in an `mdat` box of its own. */
        val mdatPerItem: Boolean = false,
        val handler: String = "pict",
        val tail: ByteArray = ByteArray(0),
        val topLevelExtra: List<ByteArray> = emptyList(),
    )

    // ---------------------------------------------------------------- content helpers

    val EXIF_TIFF: ByteArray = TiffBuilder().build(Photos.ifd0(Photos.exifIfd(), Photos.gpsIfd()))

    /** An Exif item payload: offset to the TIFF header, "Exif\0\0", TIFF, then [padding] zero bytes. */
    fun exifItem(tiff: ByteArray = EXIF_TIFF, padding: Int = 0, withMarker: Boolean = true): ByteArray =
        if (withMarker) be32(6) + "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff + ByteArray(padding) else be32(0) + tiff + ByteArray(padding)

    fun xmpPacket(vararg attributes: Pair<String, String>): ByteArray =
        io.github.fishpimp.exiflab.metadata.fixtures.Xmp.packet(io.github.fishpimp.exiflab.metadata.fixtures.Xmp.description(attributes.toMap())).toByteArray()

    fun tile(seed: Int, size: Int = 400): ByteArray = Random(seed).nextBytes(size)

    fun hvcC(seed: Int = 1) = box("hvcC", Random(seed).nextBytes(40))
    fun av1C() = box("av1C", byteArrayOf(0x81.toByte(), 0x08, 0x0C, 0x00))
    fun ispe(width: Int, height: Int) = fullBox("ispe", 0, 0, be32(width) + be32(height))
    fun colrNclx() = box("colr", "nclx".toByteArray() + be16(1) + be16(13) + be16(6) + byteArrayOf(0x80.toByte()))
    /** A `colr` property with a minimal ICC profile: a 128-byte header (size, 'acsp' signature) and an empty tag table. */
    fun colrIcc(): ByteArray {
        val profile = ByteArray(132)
        be32(profile.size).copyInto(profile, 0)
        "mntrRGB XYZ ".toByteArray().copyInto(profile, 12)
        "acsp".toByteArray().copyInto(profile, 36)
        return box("colr", "prof".toByteArray() + profile)
    }
    fun pixi() = fullBox("pixi", 0, 0, byteArrayOf(3, 8, 8, 8))
    fun irot(angle: Int) = box("irot", byteArrayOf(angle.toByte()))

    /**
     * A 2 x 2 grid image (grid descriptor in `idat`) of four HEVC tiles, with optional Exif and XMP
     * items described by `cdsc`, like an iPhone photo.
     */
    fun gridPhoto(exif: ByteArray? = exifItem(), xmp: ByteArray? = null, tileExtents: Int = 1): Spec {
        val grid = byteArrayOf(0, 0, 1, 1) + be16(512) + be16(512)
        val items = mutableListOf(Item(1, "grid", grid, inIdat = true))
        for (id in 2..5) items += Item(id, "hvc1", tile(id, 300 + id * 50), extents = tileExtents)
        exif?.let { items += Item(6, "Exif", it) }
        xmp?.let { items += Item(7, "mime", it, contentType = "application/rdf+xml") }
        return Spec(
            items = items,
            primary = 1,
            refs = listOfNotNull(
                Ref("dimg", 1, listOf(2, 3, 4, 5)),
                exif?.let { Ref("cdsc", 6, listOf(1)) },
                xmp?.let { Ref("cdsc", 7, listOf(1)) },
            ),
            properties = listOf(hvcC(), ispe(256, 256), ispe(512, 512), colrNclx(), pixi(), irot(1)),
            associations = (2..5).associate { it to listOf(-1, 2) } + mapOf(1 to listOf(3, 4, 5, -6)),
        )
    }

    /** A single coded image (`hvc1`, or `av01` for AVIF) with optional Exif and XMP. */
    fun simplePhoto(exif: ByteArray? = exifItem(), xmp: ByteArray? = null, avif: Boolean = false, imageExtents: Int = 1): Spec {
        val items = mutableListOf(Item(1, if (avif) "av01" else "hvc1", tile(1, 2000), extents = imageExtents))
        items += Item(2, "hvc1", tile(2, 300))
        exif?.let { items += Item(3, "Exif", it) }
        xmp?.let { items += Item(4, "mime", it, contentType = "application/rdf+xml") }
        return Spec(
            items = items,
            primary = 1,
            refs = listOfNotNull(Ref("thmb", 2, listOf(1)), exif?.let { Ref("cdsc", 3, listOf(1)) }, xmp?.let { Ref("cdsc", 4, listOf(1)) }),
            properties = listOf(if (avif) av1C() else hvcC(), ispe(640, 480), ispe(160, 120), colrNclx(), irot(3)),
            associations = mapOf(1 to listOf(-1, 2, 4, -5), 2 to listOf(-1, 3)),
            brand = if (avif) "avif" else "heic",
        )
    }

    // ---------------------------------------------------------------- builder

    fun build(spec: Spec): ByteArray {
        val ftyp = box("ftyp", spec.brand.toByteArray() + be32(0) + "mif1".toByteArray() + spec.brand.toByteArray())
        val idatItems = spec.items.filter { it.inIdat }
        val idat = idatItems.fold(ByteArray(0)) { acc, item -> acc + item.data }
        val mdatItems = (spec.dataOrder?.map { id -> spec.items.single { it.id == id } } ?: spec.items.filterNot { it.inIdat }).filter { it.sameDataAs == null }
        val trackChunks = spec.track?.let { track -> track.samples.chunked(track.samplesPerChunk) }.orEmpty()

        fun layout(metaSize: Int): ByteArray {
            // Positions of item data and chunks.
            val positions = HashMap<Int, List<LongArray>>()
            val mdats = mutableListOf<ByteArray>()
            var position = ftyp.size.toLong() + if (spec.metaAfterMdat) 0 else metaSize + spec.freeAfterMeta
            val mdatPayloadStart = position + 8
            val chunkOffsets = mutableListOf<Long>()
            if (spec.mdatPerItem) {
                for (item in mdatItems) {
                    positions[item.id] = split(item, position + 8)
                    mdats += box("mdat", item.data)
                    position += 8 + item.data.size
                }
            } else {
                val payload = ByteArrayOutputStream()
                for (item in mdatItems) {
                    positions[item.id] = split(item, position + 8 + payload.size())
                    payload.write(item.data)
                }
                for (chunk in trackChunks) {
                    chunkOffsets += position + 8 + payload.size()
                    chunk.forEach(payload::write)
                }
                mdats += box("mdat", payload.toByteArray())
                position += 8 + payload.size()
            }
            for (item in spec.items) item.sameDataAs?.let { positions[item.id] = positions.getValue(it) }
            val moov = spec.track?.let { moov(it, trackChunks, chunkOffsets) }
            val meta = meta(spec, positions, idatItems, idat, mdatPayloadStart)
            check(meta.size == metaSize)
            val free = if (spec.freeAfterMeta > 0) box("free", ByteArray(spec.freeAfterMeta - 8)) else ByteArray(0)
            val out = ByteArrayOutputStream()
            out.write(ftyp)
            if (!spec.metaAfterMdat) {
                out.write(meta)
                out.write(free)
            }
            mdats.forEach(out::write)
            if (spec.metaAfterMdat) out.write(meta)
            moov?.let(out::write)
            spec.topLevelExtra.forEach(out::write)
            out.write(spec.tail)
            return out.toByteArray()
        }
        // Field sizes are fixed, so the meta box measured with placeholder offsets has its final size.
        return layout(meta(spec, emptyMap(), idatItems, idat, 0).size)
    }

    private fun split(item: Item, start: Long): List<LongArray> {
        val size = item.data.size.toLong()
        val parts = item.extents
        return (0 until parts).map { k ->
            val from = size * k / parts
            val to = size * (k + 1) / parts
            longArrayOf(start + from, to - from)
        }
    }

    private fun meta(spec: Spec, positions: Map<Int, List<LongArray>>, idatItems: List<Item>, idat: ByteArray, mdatPayloadStart: Long): ByteArray {
        val hdlr = fullBox("hdlr", 0, 0, be32(0) + spec.handler.toByteArray() + ByteArray(12) + byteArrayOf(0))
        val pitm = fullBox("pitm", 0, 0, be16(spec.primary))
        val infes = spec.items.map { item ->
            val body = (if (item.id > 0xFFFF) be32(item.id) else be16(item.id)) + be16(0) + item.type.toByteArray() + byteArrayOf(0) +
                (item.contentType?.let { it.toByteArray() + 0.toByte() } ?: ByteArray(0))
            fullBox("infe", if (item.id > 0xFFFF) 3 else 2, if (item.type == "Exif" || item.type == "mime") 1 else 0, body)
        }
        val iinf = fullBox("iinf", 0, 0, be16(infes.size) + infes.fold(ByteArray(0)) { acc, b -> acc + b })
        val version = spec.ilocVersion
        val iloc = ByteArrayOutputStream()
        iloc.write((spec.offsetSize shl 4) or spec.lengthSize)
        iloc.write((spec.baseOffsetSize shl 4) or if (version >= 1) spec.indexSize else 0)
        iloc.write(if (version < 2) be16(spec.items.size) else be32(spec.items.size))
        var idatOffset = 0L
        for (item in spec.items) {
            iloc.write(if (version < 2) be16(item.id) else be32(item.id))
            val method = item.constructionMethod ?: if (item.inIdat) 1 else 0
            if (version >= 1) iloc.write(be16(method))
            iloc.write(be16(spec.dataReference))
            val extents = if (item.inIdat) {
                listOf(longArrayOf(idatOffset, item.data.size.toLong())).also { idatOffset += item.data.size }
            } else {
                positions[item.id] ?: List(item.extents) { longArrayOf(0, 0) }
            }
            val base = if (spec.baseRelative && !item.inIdat) mdatPayloadStart else 0L
            iloc.write(uint(base, spec.baseOffsetSize))
            iloc.write(be16(extents.size))
            for (extent in extents) {
                if (version >= 1 && spec.indexSize > 0) iloc.write(uint(0, spec.indexSize))
                iloc.write(uint(extent[0] - base, spec.offsetSize))
                iloc.write(uint(extent[1], spec.lengthSize))
            }
        }
        val ilocBox = fullBox("iloc", version, 0, iloc.toByteArray())
        val iref = if (spec.refs.isEmpty()) ByteArray(0) else fullBox(
            "iref", 0, 0,
            spec.refs.fold(ByteArray(0)) { acc, ref -> acc + box(ref.type, be16(ref.from) + be16(ref.to.size) + ref.to.fold(ByteArray(0)) { a, id -> a + be16(id) }) },
        )
        val ipco = box("ipco", *spec.properties.toTypedArray())
        val ipma = fullBox(
            "ipma", 0, 0,
            be32(spec.associations.size) + spec.associations.entries.sortedBy { it.key }.fold(ByteArray(0)) { acc, (id, indexes) ->
                acc + be16(id) + byteArrayOf(indexes.size.toByte()) + indexes.map { if (it < 0) (0x80 or -it).toByte() else it.toByte() }.toByteArray()
            },
        )
        val iprp = if (spec.properties.isEmpty()) ByteArray(0) else box("iprp", ipco, ipma)
        val idatBox = if (idatItems.isEmpty()) ByteArray(0) else box("idat", idat)
        return fullBox("meta", 0, 0, hdlr + pitm + iinf + iref + iprp + idatBox + ilocBox + spec.extraMeta.fold(ByteArray(0)) { acc, b -> acc + b })
    }

    private fun moov(track: Track, chunks: List<List<ByteArray>>, offsets: List<Long>): ByteArray {
        val stsd = fullBox("stsd", 0, 0, be32(1) + box("hvc1", ByteArray(6) + be16(1) + Random(5).nextBytes(70)))
        val stts = fullBox("stts", 0, 0, be32(1) + be32(track.samples.size) + be32(1))
        val stsc = fullBox("stsc", 0, 0, be32(1) + be32(1) + be32(track.samplesPerChunk) + be32(1)).let { full ->
            // A last chunk with fewer samples gets a run of its own.
            val last = chunks.lastOrNull()?.size ?: track.samplesPerChunk
            if (last == track.samplesPerChunk) full else fullBox("stsc", 0, 0, be32(2) + be32(1) + be32(track.samplesPerChunk) + be32(1) + be32(chunks.size) + be32(last) + be32(1))
        }
        val stsz = fullBox("stsz", 0, 0, be32(0) + be32(track.samples.size) + track.samples.fold(ByteArray(0)) { acc, s -> acc + be32(s.size) })
        val stco = if (track.wide) {
            fullBox("co64", 0, 0, be32(offsets.size) + offsets.fold(ByteArray(0)) { acc, o -> acc + ByteBuffer.allocate(8).putLong(o).array() })
        } else {
            fullBox("stco", 0, 0, be32(offsets.size) + offsets.fold(ByteArray(0)) { acc, o -> acc + be32(o.toInt()) })
        }
        val dref = fullBox("dref", 0, 0, be32(1) + fullBox("url ", 0, 1, ByteArray(0)))
        val minf = box("minf", box("dinf", dref), box("stbl", stsd, stts, stsc, stsz, stco))
        return box("moov", box("trak", box("mdia", minf)))
    }

    fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { acc, part -> acc + part }
        return be32(body.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + body
    }

    fun fullBox(type: String, version: Int, flags: Int, body: ByteArray): ByteArray = box(type, byteArrayOf(version.toByte()) + be32(flags).copyOfRange(1, 4), body)

    fun be16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())
    fun be32(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
    private fun uint(value: Long, size: Int): ByteArray = when (size) {
        0 -> ByteArray(0)
        4 -> be32(value.toInt())
        8 -> ByteBuffer.allocate(8).putLong(value).array()
        else -> error("size $size")
    }

    // ---------------------------------------------------------------- independent parser

    class Box(val type: String, val start: Int, val headerSize: Int, val end: Int) {
        val payloadStart get() = start + headerSize
    }

    class Location(val method: Int, val base: Long, val extents: List<LongArray>, val dataReference: Int)

    class Info(val id: Long, val type: String, val contentType: String)

    /** The parts of a HEIF file the tests check, parsed independently of the writer. */
    class Parsed(val bytes: ByteArray) {
        val topLevel: List<Box> = boxes(bytes, 0, bytes.size)
        val meta: Box = topLevel.single { it.type == "meta" }
        private val metaChildren = boxes(bytes, meta.payloadStart + 4, meta.end)
        private fun child(type: String) = metaChildren.firstOrNull { it.type == type }

        val primary: Long = child("pitm")!!.let { u16(bytes, it.payloadStart + 4).toLong() }
        val infos: List<Info>
        val locations: Map<Long, Location>
        val ilocVersion: Int
        val offsetSize: Int
        val baseOffsetSize: Int
        val refs: List<Triple<String, Long, List<Long>>>
        val idat: ByteArray? = child("idat")?.let { bytes.copyOfRange(it.payloadStart, it.end) }
        val properties: List<ByteArray>
        val associations: Map<Long, List<Int>>

        init {
            val iinf = child("iinf")!!
            val infoVersion = bytes[iinf.payloadStart].toInt()
            infos = boxes(bytes, iinf.payloadStart + if (infoVersion == 0) 6 else 8, iinf.end).map { infe ->
                val version = bytes[infe.payloadStart].toInt()
                var p = infe.payloadStart + 4
                val id = if (version == 3) u32(bytes, p).also { p += 4 } else u16(bytes, p).toLong().also { p += 2 }
                p += 2
                val type = String(bytes, p, 4, Charsets.ISO_8859_1)
                p += 4
                while (bytes[p].toInt() != 0) p++
                p++
                val contentType = if (type == "mime") String(bytes, p, (p until infe.end).first { bytes[it].toInt() == 0 } - p) else ""
                Info(id, type, contentType)
            }
            val iloc = child("iloc")!!
            var p = iloc.payloadStart
            val version = bytes[p].toInt()
            ilocVersion = version
            p += 4
            offsetSize = (bytes[p].toInt() and 0xFF) shr 4
            val lengthSize = bytes[p].toInt() and 0x0F
            baseOffsetSize = (bytes[p + 1].toInt() and 0xFF) shr 4
            val indexSize = if (version >= 1) bytes[p + 1].toInt() and 0x0F else 0
            p += 2
            fun read(size: Int): Long = when (size) {
                0 -> 0L
                2 -> u16(bytes, p).toLong()
                4 -> u32(bytes, p)
                else -> ByteBuffer.wrap(bytes, p, 8).long
            }.also { p += size }
            val count = read(if (version < 2) 2 else 4)
            val result = LinkedHashMap<Long, Location>()
            repeat(count.toInt()) {
                val id = read(if (version < 2) 2 else 4)
                val method = if (version >= 1) read(2).toInt() and 0x0F else 0
                val dataReference = read(2).toInt()
                val base = read(baseOffsetSize)
                val extents = List(read(2).toInt()) {
                    read(indexSize)
                    longArrayOf(read(offsetSize), read(lengthSize))
                }
                result[id] = Location(method, base, extents, dataReference)
            }
            check(p == iloc.end) { "iloc has trailing bytes" }
            locations = result
            refs = child("iref")?.let { iref ->
                val refVersion = bytes[iref.payloadStart].toInt()
                boxes(bytes, iref.payloadStart + 4, iref.end).map { ref ->
                    var q = ref.payloadStart
                    fun id(): Long = if (refVersion == 0) u16(bytes, q).toLong().also { q += 2 } else u32(bytes, q).also { q += 4 }
                    val from = id()
                    val n = u16(bytes, q)
                    q += 2
                    Triple(ref.type, from, List(n) { id() })
                }
            }.orEmpty()
            val iprp = child("iprp")
            val iprpChildren = iprp?.let { boxes(bytes, it.payloadStart, it.end) }.orEmpty()
            properties = iprpChildren.firstOrNull { it.type == "ipco" }?.let { ipco -> boxes(bytes, ipco.payloadStart, ipco.end).map { bytes.copyOfRange(it.start, it.end) } }.orEmpty()
            associations = iprpChildren.firstOrNull { it.type == "ipma" }?.let { ipma ->
                var q = ipma.payloadStart
                val ipmaVersion = bytes[q].toInt()
                val flags = bytes[q + 3].toInt()
                q += 4
                val n = u32(bytes, q).toInt()
                q += 4
                (0 until n).associate {
                    val id = if (ipmaVersion < 1) u16(bytes, q).toLong().also { q += 2 } else u32(bytes, q).also { q += 4 }
                    val k = bytes[q++].toInt() and 0xFF
                    id to List(k) { if (flags and 1 != 0) u16(bytes, q).also { q += 2 } else (bytes[q++].toInt() and 0xFF) }
                }
            }.orEmpty()
        }

        fun info(id: Long) = infos.single { it.id == id }

        val exifIds get() = infos.filter { it.type == "Exif" }.map { it.id }
        val xmpIds get() = infos.filter { it.type == "mime" && it.contentType == "application/rdf+xml" }.map { it.id }
        val metadataIds get() = exifIds + xmpIds

        /** Absolute file ranges of an item located by file offset. */
        fun fileRanges(id: Long): List<LongArray> {
            val location = locations.getValue(id)
            check(location.method == 0)
            return location.extents.map { longArrayOf(location.base + it[0], it[1]) }
        }

        fun data(id: Long): ByteArray {
            val location = locations.getValue(id)
            val out = ByteArrayOutputStream()
            for (extent in location.extents) {
                val start = (location.base + extent[0]).toInt()
                val length = extent[1].toInt()
                when (location.method) {
                    0 -> out.write(bytes, start, length)
                    1 -> out.write(idat!!, start, length)
                    else -> error("method ${location.method}")
                }
            }
            return out.toByteArray()
        }

        fun box(type: String) = topLevel.single { it.type == type }
    }

    fun boxes(bytes: ByteArray, start: Int, end: Int): List<Box> {
        val result = mutableListOf<Box>()
        var p = start
        while (p + 8 <= end) {
            var size = u32(bytes, p)
            var header = 8
            if (size == 1L) {
                size = ByteBuffer.wrap(bytes, p + 8, 8).long
                header = 16
            } else if (size == 0L) {
                size = (end - p).toLong()
            }
            val type = String(bytes, p + 4, 4, Charsets.ISO_8859_1)
            check(size >= header && p + size <= end) { "box $type at $p overruns" }
            result += Box(type, p, header, (p + size).toInt())
            p += size.toInt()
        }
        return result
    }

    fun u16(bytes: ByteArray, at: Int) = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
    fun u32(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes, at, 4).int.toLong() and 0xFFFFFFFFL

    /** Sample chunks of the first track: (offset, bytes). */
    fun chunks(bytes: ByteArray): List<Pair<Long, Int>> {
        val parsed = boxes(bytes, 0, bytes.size)
        val moov = parsed.single { it.type == "moov" }
        fun descend(box: Box, type: String) = boxes(bytes, box.payloadStart, box.end).single { it.type == type }
        val stbl = descend(descend(descend(descend(moov, "trak"), "mdia"), "minf"), "stbl")
        val children = boxes(bytes, stbl.payloadStart, stbl.end)
        val co = children.single { it.type == "stco" || it.type == "co64" }
        val count = u32(bytes, co.payloadStart + 4).toInt()
        val offsets = List(count) {
            if (co.type == "co64") ByteBuffer.wrap(bytes, co.payloadStart + 8 + it * 8, 8).long else u32(bytes, co.payloadStart + 8 + it * 4)
        }
        val stsz = children.single { it.type == "stsz" }
        val sizes = List(u32(bytes, stsz.payloadStart + 8).toInt()) { u32(bytes, stsz.payloadStart + 12 + it * 4).toInt() }
        val stsc = children.single { it.type == "stsc" }
        val perChunk = u32(bytes, stsc.payloadStart + 12).toInt()
        return offsets.mapIndexed { index, offset -> offset to sizes.drop(index * perChunk).take(perChunk).sum() }
    }

    // ---------------------------------------------------------------- assertions

    /** Every image item keeps its type, data, properties and associations; references between image items stay. */
    fun assertImageDataIntact(before: ByteArray, after: ByteArray) {
        val old = Parsed(before)
        val new = Parsed(after)
        assertThat(new.primary).isEqualTo(old.primary)
        assertThat(new.properties.map { it.toList() }).isEqualTo(old.properties.map { it.toList() })
        for (info in old.infos.filter { it.id !in old.metadataIds }) {
            assertThat(new.info(info.id).type).isEqualTo(info.type)
            assertThat(new.data(info.id)).isEqualTo(old.data(info.id))
            assertThat(new.associations[info.id]).isEqualTo(old.associations[info.id])
        }
        val imageRefs = { parsed: Parsed -> parsed.refs.filter { it.second !in parsed.metadataIds }.map { "${it.first}:${it.second}:${it.third}" } }
        assertThat(imageRefs(new)).isEqualTo(imageRefs(old))
    }

    /** No id is referenced without an `infe`, metadata items describe the primary item, and all data lies inside the file. */
    fun assertConsistent(file: ByteArray) {
        val parsed = Parsed(file)
        val ids = parsed.infos.map { it.id }.toSet()
        assertThat(ids).hasSize(parsed.infos.size)
        assertThat(parsed.locations.keys).containsExactlyElementsIn(ids.filter { parsed.locations.containsKey(it) })
        assertThat(ids).containsAtLeastElementsIn(parsed.locations.keys)
        for ((_, from, to) in parsed.refs) {
            assertThat(ids).contains(from)
            assertThat(ids).containsAtLeastElementsIn(to)
        }
        assertThat(ids).containsAtLeastElementsIn(parsed.associations.keys)
        for (id in parsed.metadataIds) {
            assertThat(parsed.refs.filter { it.first == "cdsc" && it.second == id }.flatMap { it.third }).contains(parsed.primary)
            assertThat(parsed.locations).containsKey(id)
        }
        for ((id, location) in parsed.locations) {
            for (extent in location.extents) {
                val limit = if (location.method == 1) parsed.idat!!.size.toLong() else file.size.toLong()
                assertThat(location.base + extent[0] + extent[1]).isAtMost(limit)
                assertThat(id).isAtLeast(1L)
            }
        }
        // Top-level boxes tile the file.
        assertThat(parsed.topLevel.last().end).isEqualTo(file.size)
    }
}
