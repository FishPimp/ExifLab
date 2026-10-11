package io.github.fishpimp.exiflab.metadata.write.heif

import io.github.fishpimp.exiflab.metadata.CorruptImageException
import io.github.fishpimp.exiflab.metadata.io.SeekableSource
import io.github.fishpimp.exiflab.metadata.write.COPY_BUFFER
import io.github.fishpimp.exiflab.metadata.write.MetadataBlock
import io.github.fishpimp.exiflab.metadata.write.MetadataChanges
import io.github.fishpimp.exiflab.metadata.write.UnsupportedEditException
import io.github.fishpimp.exiflab.metadata.write.bytesAt
import io.github.fishpimp.exiflab.metadata.write.copyRange
import io.github.fishpimp.exiflab.metadata.write.latin1
import io.github.fishpimp.exiflab.metadata.write.tiff.ExifEditor
import io.github.fishpimp.exiflab.metadata.write.tiff.TiffParser
import io.github.fishpimp.exiflab.metadata.write.tiff.TiffSerializer
import io.github.fishpimp.exiflab.metadata.write.xmp.XmpPackets
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Rewrites the Exif and XMP items of a HEIF or AVIF file without touching its image data.
 *
 * Exif (item type `Exif`: a 32-bit offset to the TIFF header, usually skipping "Exif\0\0", then
 * TIFF) and XMP (item type `mime`, content type `application/rdf+xml`) are edited with the same
 * TIFF and XMP code as JPEG. A changed payload is written over the old one when it fits (Exif is
 * zero-padded to the old size, XMP gets XMP padding), otherwise it goes to a new `mdat` at the end
 * of the file (with some slack for later edits) and the old bytes are zeroed, or the old `mdat`
 * is dropped when nothing else lives in it. Items stored in `idat` stay there. New items get the
 * next free id, an `infe`, an `iloc` entry and a `cdsc` reference to the primary item; small new
 * items go into `idat` when the file has one. Removed items leave no `iinf`, `iloc`, `iref` or
 * `ipma` entry behind. Edits go to the Exif/XMP item that describes the primary image; a block
 * removal drops every item of its kind.
 *
 * Only the `meta` box changes size. A `free` box right after it absorbs the difference when it
 * can (a shrinking `meta` gets one); otherwise every file-offset `iloc` extent (and `stco`/`co64`
 * chunk offset of image sequences) behind it is moved, widening offset fields to 64 bits when
 * needed. Image items, their properties (`ispe`, `hvcC`, `av1C`, `colr`, `pixi`, `irot`, `imir`,
 * ...), derived items (grids, overlays) and unknown boxes are copied byte for byte. Structures
 * that cannot be rewritten safely are refused with [UnsupportedEditException]. HEIF has no place
 * for IPTC-IIM, and its color profile (`colr`) is image description, so neither is written.
 */
internal class HeifWriter {
    /** A planned output file: the [segments] to write and the digest plan of the result. */
    class Output internal constructor(private val segments: List<Segment>, val digestPlan: HeifDigestPlan, val length: Long) {
        fun write(source: SeekableSource, out: OutputStream) {
            val buffer = ByteArray(COPY_BUFFER)
            for (segment in segments) {
                when (segment) {
                    is Segment.Data -> out.write(segment.bytes)
                    is Segment.Copy -> {
                        var position = segment.start
                        for (overlay in segment.overlays) {
                            copyRange(source, position, overlay.at - position, out, buffer)
                            overlay.writeTo(out)
                            position = overlay.at + overlay.length
                        }
                        copyRange(source, position, segment.end - position, out, buffer)
                    }
                }
            }
        }
    }

    internal sealed class Segment {
        abstract val size: Long

        /** Source bytes `[start, end)` with [overlays] written over parts of them. */
        class Copy(val start: Long, val end: Long, val overlays: List<Overlay>) : Segment() {
            override val size: Long get() = end - start
        }

        class Data(val bytes: ByteArray) : Segment() {
            override val size: Long get() = bytes.size.toLong()
        }
    }

    /** Bytes written over the source at [at]: [bytes], or zeros when null. */
    internal class Overlay(val at: Long, val length: Long, val bytes: ByteArray?) {
        val end: Long get() = at + length

        fun clip(from: Long, to: Long): Overlay? {
            val start = maxOf(at, from)
            val stop = minOf(end, to)
            if (stop <= start) return null
            return Overlay(start, stop - start, bytes?.copyOfRange((start - at).toInt(), (stop - at).toInt()))
        }

        fun writeTo(out: OutputStream) {
            if (bytes != null) {
                out.write(bytes)
                return
            }
            val zeros = ByteArray(minOf(length, COPY_BUFFER.toLong()).toInt())
            var left = length
            while (left > 0) {
                val n = minOf(left, zeros.size.toLong()).toInt()
                out.write(zeros, 0, n)
                left -= n
            }
        }
    }

    /** New item data and how to fit it into [capacity] bytes for an in-place rewrite (null when it does not fit). */
    private class Payload(val bytes: ByteArray, val fit: (capacity: Int) -> ByteArray?)

    private enum class Place { InPlace, Idat, Mdat }

    /** Where a new or changed item's data goes. [type] is set for items that do not exist yet. */
    private class Placement(val id: Long, val type: String?, val payload: Payload) {
        var place = Place.Mdat
        var bytes: ByteArray = payload.bytes

        /** Offset within the new `mdat` payload or within `idat`. */
        var offset = 0L
    }

    /** One copied source range and where it lands in the output. */
    private class CopyMap(val start: Long, var end: Long, val outStart: Long)

    private class Layout(val segments: MutableList<Segment>, val metaIndex: Int, val movieIndex: Int?, copies: List<CopyMap>, val newDataStart: Long) {
        private val merged: List<CopyMap> = buildList {
            for (copy in copies) {
                val last = lastOrNull()
                if (last != null && last.end == copy.start && last.outStart + (last.end - last.start) == copy.outStart) {
                    last.end = copy.end
                } else {
                    add(CopyMap(copy.start, copy.end, copy.outStart))
                }
            }
        }

        /** Output position of the source bytes `[start, start + length)`, or null when they are not copied as one piece. */
        fun map(start: Long, length: Long): Long? {
            val copy = merged.firstOrNull { start >= it.start && start + length <= it.end && (length > 0 || start < it.end) } ?: return null
            return copy.outStart + (start - copy.start)
        }
    }

    fun prepare(source: SeekableSource, changes: MetadataChanges, skipped: MutableList<String>): Output {
        val file = HeifStructure.read(source)
        val meta = file.parseMeta()
        val movie = file.parseMovie()
        if (meta.handlerType != "pict") throw UnsupportedEditException("This HEIF file does not hold a still image ('${meta.handlerType}' handler)")
        // Validates every image item, property and track the digest covers before anything changes.
        HeifDigest.plan(meta, movie, file.length)
        validateLocations(meta, file.length)

        if (changes.iptc.isNotEmpty()) skipped += "HEIF files cannot hold IPTC-IIM; ${changes.iptc.size} IPTC change(s) were not written"
        if (MetadataBlock.IccProfile in changes.removeBlocks && hasIccProfile(meta)) {
            skipped += "The color profile of a HEIF image is part of its image description and was kept"
        }

        resolveLengths(meta, file.length)
        // Absolute file ranges (start, length) of every item located by file offset, before any change.
        val sourceExtents: Map<Long, List<LongArray>> = meta.locations.entries.filter { it.constructionMethod == 0 }
            .associate { entry -> entry.id to entry.extents.map { longArrayOf(entry.baseOffset + it.offset, it.length) } }
        val originalBases = meta.locations.entries.associate { it.id to it.baseOffset }
        val originalOffsets = meta.locations.entries.associate { entry -> entry.id to entry.extents.map { it.offset } }
        val idatExtents: Map<Long, List<LongArray>> = meta.locations.entries.filter { it.constructionMethod == 1 }
            .associate { entry -> entry.id to entry.extents.map { longArrayOf(entry.baseOffset + it.offset, it.length) } }
        val chunkRanges = movie?.tracks.orEmpty().flatMap { track ->
            track.chunkOffsets.indices.filter { track.chunkSizes[it] > 0 }.map { longArrayOf(track.chunkOffsets[it], track.chunkSizes[it]) }
        }

        val removed = mutableSetOf<Long>()
        val placements = mutableListOf<Placement>()
        var nextId = (meta.usedIds().maxOrNull() ?: 0L) + 1
        fun plan(target: ItemInfo?, others: List<ItemInfo>, removeAll: Boolean, payload: Payload?, type: String) {
            for (item in others) {
                when {
                    item === target && payload != null -> placements += Placement(item.id, null, payload)
                    item === target || removeAll -> removed += item.id
                }
            }
            if (target == null && payload != null) placements += Placement(nextId++, type, payload)
        }

        if (ExifEditor.affects(changes)) {
            val items = meta.infos.filter { it.isExif }
            val target = preferred(meta, items)
            val removeAll = MetadataBlock.Exif in changes.removeBlocks
            var prefix = EXIF_PREFIX
            val original = if (target != null && !removeAll) {
                val (itemPrefix, tiff) = splitExif(itemData(source, file, meta, target)) ?: throw UnsupportedEditException("The HEIF Exif item is damaged and cannot be edited")
                prefix = itemPrefix
                TiffParser.parse(tiff, skipped)
            } else {
                null
            }
            val tiff = ExifEditor.apply(original, changes, jpeg = null, skipped)?.let { TiffSerializer.serialize(it, MAX_PAYLOAD, skipped) }
            plan(target, items, removeAll, tiff?.let { exifPayload(prefix, it) }, ItemInfo.EXIF_TYPE)
        }
        if (XmpPackets.affects(changes)) {
            val items = meta.infos.filter { it.isXmp }
            val removeAll = MetadataBlock.Xmp in changes.removeBlocks
            // A compressed packet is only ever removed; a replacement is stored uncompressed in a new item.
            val target = preferred(meta, items)?.takeUnless { it.contentEncoding.isNotEmpty() && removeAll }
            if (target != null && target.contentEncoding.isNotEmpty()) {
                throw UnsupportedEditException("The HEIF XMP item is compressed (${target.contentEncoding}) and cannot be edited")
            }
            val original = if (target != null && !removeAll) XmpPackets.parse(itemData(source, file, meta, target)) else null
            val payload = XmpPackets.edit(original, changes)?.let { xmp ->
                // XMPCore pads to 2 KB unless told otherwise; slack is added only where the packet is placed.
                val compact = XmpPackets.serialize(xmp, padding = 1)
                Payload(compact) { capacity -> XmpPackets.serializeExact(xmp, capacity) ?: compact.takeIf { it.size <= capacity } }
            }
            plan(target, items, removeAll, payload, ItemInfo.MIME_TYPE)
        }
        if (placements.any { it.type != null } && meta.primaryId == null) throw UnsupportedEditException("The HEIF file has no primary image to attach metadata to")

        // In place when the old data is one extent of its own, outside meta and moov, and the new data fits.
        for (placement in placements) {
            val location = meta.locations[placement.id]
            val extent = sourceExtents[placement.id]?.singleOrNull()
            if (location != null && extent != null && extent[1] in 1..MAX_PAYLOAD.toLong()) {
                val start = extent[0]
                val length = extent[1]
                val exclusive = sourceExtents.none { (id, ranges) -> id != placement.id && ranges.any { overlaps(it, start, length) } } &&
                    chunkRanges.none { overlaps(it, start, length) }
                val inData = start >= file.tailStart || file.boxes.any { it.type !in STRUCTURE_BOXES && it.contains(start, length) }
                val fitted = if (exclusive && inData) placement.payload.fit(length.toInt()) else null
                if (fitted != null) {
                    placement.place = Place.InPlace
                    placement.bytes = fitted
                    continue
                }
            }
            placement.place = when {
                location?.constructionMethod == 1 -> Place.Idat
                location == null && meta.idat != null && placement.bytes.size <= IDAT_LIMIT -> Place.Idat
                else -> Place.Mdat
            }
        }

        val moving = removed + placements.filter { it.place != Place.InPlace && meta.locations[it.id] != null }.map { it.id }
        val liveRanges = sourceExtents.filterKeys { it !in moving }.values.flatten().filter { it[1] > 0 } + chunkRanges
        val deadRanges = sourceExtents.filterKeys { it in moving }.values.flatten().filter { it[1] > 0 }
        val dropped = file.boxes.filter { box ->
            box.type == "mdat" && deadRanges.any { overlaps(it, box.start, box.size) } && liveRanges.none { overlaps(it, box.start, box.size) }
        }.toSet()

        val overlays = mutableListOf<Overlay>()
        for (placement in placements.filter { it.place == Place.InPlace }) {
            val (start, length) = sourceExtents.getValue(placement.id).single().let { it[0] to it[1] }
            val padded = if (placement.bytes.size.toLong() == length) placement.bytes else placement.bytes.copyOf(length.toInt())
            overlays += Overlay(start, length, padded)
            val location = meta.locations[placement.id]!!
            if (location.extents.single().length != placement.bytes.size.toLong()) {
                location.extents.single().length = placement.bytes.size.toLong()
                meta.markDirty()
            }
        }
        // Old metadata bytes nothing refers to any more are zeroed, so stripped values do not linger.
        for (range in subtract(merge(deadRanges), merge(liveRanges))) overlays += Overlay(range[0], range[1], null)
        overlays.sortBy { it.at }

        rebuildIdat(meta, idatExtents, moving, placements.filter { it.place == Place.Idat })
        for (id in removed) meta.removeItem(id)
        var mdatSize = 0L
        for (placement in placements) {
            if (placement.place == Place.InPlace) continue
            if (placement.place == Place.Mdat) {
                // Some slack (zeros after Exif, XMP padding) lets later edits rewrite the item in place.
                placement.bytes = placement.payload.fit(roundUp(placement.bytes.size + MDAT_SLACK, MDAT_SLACK)) ?: placement.bytes
                placement.offset = mdatSize
                mdatSize += placement.bytes.size
            }
            val method = if (placement.place == Place.Idat) 1 else 0
            val extents = mutableListOf(Extent(0, placement.offset, placement.bytes.size.toLong()))
            val existing = meta.locations[placement.id]
            if (existing != null) {
                existing.constructionMethod = method
                existing.baseOffset = 0
                existing.extents.clear()
                existing.extents += extents
                meta.markDirty()
            } else {
                val info = ItemInfo(placement.id, placement.type!!, if (placement.type == ItemInfo.MIME_TYPE) ItemInfo.XMP_CONTENT_TYPE else "", 0, raw = null)
                meta.addItem(info, ItemLocation(placement.id, method, 0, 0, extents), describes = meta.primaryId!!)
            }
        }
        val mdatPlacements = placements.filter { it.place == Place.Mdat }.associateBy { it.id }
        val newData = if (mdatPlacements.isEmpty()) null else ByteArray(mdatSize.toInt()).also { data ->
            for (placement in mdatPlacements.values) placement.bytes.copyInto(data, placement.offset.toInt())
        }

        val toEndHeaders = file.boxes.filter { it.toEnd }.associateWith { source.bytesAt(it.start, it.headerSize) }
        var metaBytes = meta.bytes()
        // Item positions depend on the size of meta, which depends on the iloc field sizes the positions need;
        // sizes only ever widen, so a few passes settle it.
        var layout: Layout? = null
        var passes = 0
        while (layout == null && passes++ < MAX_PASSES) {
            val candidate = arrange(file, metaBytes, dropped, overlays, liveRanges, newData, toEndHeaders)
            for (entry in meta.locations.entries) {
                val placement = mdatPlacements[entry.id]
                if (placement != null) {
                    entry.extents.single().offset = candidate.newDataStart + placement.offset
                    continue
                }
                if (entry.constructionMethod != 0) continue
                val extents = sourceExtents[entry.id] ?: continue
                if (relocate(entry, extents, originalBases.getValue(entry.id), originalOffsets.getValue(entry.id), candidate)) meta.markDirty()
            }
            val again = meta.bytes()
            if (again.size == metaBytes.size) layout = candidate
            metaBytes = again
        }
        val settled = layout ?: throw UnsupportedEditException("The HEIF layout could not be settled")
        val segments = settled.segments
        segments[settled.metaIndex] = Segment.Data(metaBytes)
        val movieBytes = settled.movieIndex?.let { index ->
            if (file.movieBox!!.toEnd && newData != null) throw UnsupportedEditException("This HEIF sequence layout cannot be extended")
            movie!!.moved { offset, size -> settled.map(offset, size) }.also { segments[index] = Segment.Data(it) }
        }
        val length = segments.sumOf { it.size }
        val digestPlan = HeifDigest.plan(HeifMeta.parse(metaBytes), movieBytes?.let(Movie::parse), length)
        return Output(segments, digestPlan, length)
    }

    /**
     * Lays the output out: boxes in their order with the new `meta`, a `free` box after it
     * absorbing the size difference when possible, dropped `mdat` boxes left out, then the new
     * `mdat` (if any) and the bytes after the last box.
     */
    private fun arrange(
        file: HeifStructure,
        metaBytes: ByteArray,
        dropped: Set<TopBox>,
        overlays: List<Overlay>,
        liveRanges: List<LongArray>,
        newData: ByteArray?,
        toEndHeaders: Map<TopBox, ByteArray>,
    ): Layout {
        val segments = mutableListOf<Segment>()
        val copies = mutableListOf<CopyMap>()
        var out = 0L
        fun data(bytes: ByteArray) {
            segments += Segment.Data(bytes)
            out += bytes.size
        }
        fun copy(start: Long, end: Long) {
            if (end <= start) return
            segments += Segment.Copy(start, end, overlays.mapNotNull { it.clip(start, end) })
            copies += CopyMap(start, end, out)
            out += end - start
        }

        val metaPosition = file.boxes.indexOf(file.metaBox)
        val delta = metaBytes.size - file.metaBox.size
        val following = file.boxes.getOrNull(metaPosition + 1)?.takeIf { box ->
            box.type in FREE_BOXES && !box.toEnd && box !in dropped && liveRanges.none { overlaps(it, box.start, box.size) } &&
                (box.size - delta).let { it == 0L || it >= 8 }
        }
        // Padding only helps when the box right after `meta` stays; a dropped box moves everything behind it anyway.
        val next = file.boxes.getOrNull(metaPosition + 1)
        val padShrinking = if (next == null) file.tailStart < file.length else next !in dropped
        var metaIndex = -1
        var movieIndex: Int? = null
        for (box in file.boxes) {
            when {
                box === file.metaBox -> {
                    metaIndex = segments.size
                    // An unchanged meta box still maps, for the rare item located by file offset inside it.
                    if (metaBytes.contentEquals(file.metaBytes)) copies += CopyMap(box.start, box.end, out)
                    data(metaBytes)
                    if (following == null && delta <= -8 && padShrinking) data(freeBox(-delta))
                }
                box === following && delta != 0L -> if (box.size - delta >= 8) data(freeBox(box.size - delta))
                box in dropped -> Unit
                box === file.movieBox -> {
                    movieIndex = segments.size
                    data(file.movieBytes!!)
                }
                box.toEnd && newData != null -> {
                    data(explicitHeader(toEndHeaders.getValue(box), box))
                    copy(box.payloadStart, box.end)
                }
                else -> copy(box.start, box.end)
            }
        }
        var newDataStart = 0L
        if (newData != null) {
            val box = BoxBytes.box("mdat", newData)
            newDataStart = out + (box.size - newData.size)
            data(box)
        }
        copy(file.tailStart, file.length)
        return Layout(segments, metaIndex, movieIndex, copies, newDataStart)
    }

    /**
     * Moves the file-offset extents of [entry] to where the layout puts their bytes. A uniform move
     * goes into `base_offset` when the item uses one; otherwise the extent offsets change. Returns
     * whether anything differs from the source.
     */
    private fun relocate(entry: ItemLocation, source: List<LongArray>, base: Long, offsets: List<Long>, layout: Layout): Boolean {
        val targets = source.map { layout.map(it[0], it[1]) ?: throw UnsupportedEditException("The HEIF item ${entry.id} has data the writer cannot place") }
        val deltas = targets.indices.map { targets[it] - source[it][0] }
        entry.baseOffset = base
        offsets.forEachIndexed { index, offset -> entry.extents[index].offset = offset }
        if (deltas.all { it == 0L }) return false
        if (base != 0L && deltas.distinct().size == 1) {
            entry.baseOffset = base + deltas.first()
        } else if (targets.all { it >= base }) {
            targets.forEachIndexed { index, target -> entry.extents[index].offset = target - base }
        } else {
            entry.baseOffset = 0
            targets.forEachIndexed { index, target -> entry.extents[index].offset = target }
        }
        return true
    }

    /**
     * Zeroes the `idat` bytes of items that move or go away (keeping bytes other items share),
     * trims zeros at the end, and appends [placements].
     */
    private fun rebuildIdat(meta: HeifMeta, extents: Map<Long, List<LongArray>>, moving: Set<Long>, placements: List<Placement>) {
        val original = meta.idat ?: return
        val dead = extents.filterKeys { it in moving }.values.flatten()
        if (dead.isEmpty() && placements.isEmpty()) return
        val live = extents.filterKeys { it !in moving }.values.flatten()
        val data = original.copyOf()
        for (range in subtract(merge(dead), merge(live))) data.fill(0, range[0].toInt(), (range[0] + range[1]).toInt())
        var size = data.size
        val liveEnd = live.maxOfOrNull { it[0] + it[1] }?.toInt() ?: 0
        while (size > liveEnd && data[size - 1].toInt() == 0) size--
        val result = ByteArrayOutputStream()
        result.write(data, 0, size)
        for (placement in placements) {
            placement.offset = result.size().toLong()
            result.write(placement.bytes)
        }
        meta.idat = result.toByteArray()
    }

    /** Every `iloc` entry must point into this file, by file offset or into `idat`, within bounds. */
    private fun validateLocations(meta: HeifMeta, fileLength: Long) {
        for (entry in meta.locations.entries) {
            if (entry.dataReferenceIndex != 0) throw UnsupportedEditException("The HEIF file keeps item data in another file")
            val limit = when (entry.constructionMethod) {
                0 -> fileLength
                1 -> (meta.idat ?: BoxBytes.damaged("idat")).size.toLong()
                else -> throw UnsupportedEditException("The HEIF file builds items from other items (construction method ${entry.constructionMethod}), which is not supported")
            }
            for (extent in entry.extents) {
                val start = entry.baseOffset + extent.offset
                if (start < 0 || start > limit || (extent.length > 0 && start + extent.length > limit)) {
                    throw CorruptImageException("HEIF item data runs past the end of the file; it may be truncated")
                }
            }
        }
    }

    /** Replaces extent lengths of 0 ("to the end") by the actual length, so data can follow them. */
    private fun resolveLengths(meta: HeifMeta, fileLength: Long) {
        for (entry in meta.locations.entries) {
            val limit = if (entry.constructionMethod == 1) meta.idat!!.size.toLong() else fileLength
            for (extent in entry.extents) if (extent.length == 0L) extent.length = limit - (entry.baseOffset + extent.offset)
        }
    }

    private fun itemData(source: SeekableSource, file: HeifStructure, meta: HeifMeta, item: ItemInfo): ByteArray {
        if (item.protectionIndex != 0) throw UnsupportedEditException("The HEIF metadata item ${item.id} is protected and cannot be edited")
        val location = meta.locations[item.id] ?: throw UnsupportedEditException("The HEIF metadata item ${item.id} has no data")
        val total = location.extents.sumOf { it.length }
        if (total > MAX_PAYLOAD) throw UnsupportedEditException("The HEIF metadata item ${item.id} is too large to edit")
        val out = ByteArrayOutputStream(total.toInt())
        for (extent in location.extents) {
            val start = location.baseOffset + extent.offset
            when (location.constructionMethod) {
                0 -> {
                    if (start + extent.length > file.length) throw CorruptImageException("HEIF item data runs past the end of the file; it may be truncated")
                    out.write(source.bytesAt(start, extent.length.toInt()))
                }
                else -> out.write(meta.idat!!, start.toInt(), extent.length.toInt())
            }
        }
        return out.toByteArray()
    }

    private fun preferred(meta: HeifMeta, items: List<ItemInfo>): ItemInfo? =
        items.firstOrNull { item -> meta.primaryId != null && meta.primaryId in meta.describes(item.id) } ?: items.firstOrNull()

    /**
     * Splits an Exif item into the bytes between its 32-bit header and the TIFF header (normally
     * "Exif\0\0") and the TIFF block. Some writers get the offset wrong; the byte-order mark is
     * then looked for where it usually is.
     */
    private fun splitExif(data: ByteArray): Pair<ByteArray, ByteArray>? {
        if (data.size < 12) return null
        fun isTiff(at: Long) = at >= 4 && at + 8 <= data.size && String(data, at.toInt(), 2, Charsets.ISO_8859_1).let { it == "II" || it == "MM" }
        val start = listOf(4 + BoxBytes.u32(data, 0), 4L, 10L).firstOrNull(::isTiff)?.toInt() ?: return null
        val prefix = data.copyOfRange(4, start).takeIf { it.size <= MAX_PREFIX } ?: EXIF_PREFIX
        return prefix to data.copyOfRange(start, data.size)
    }

    private fun exifPayload(prefix: ByteArray, tiff: ByteArray): Payload {
        val writer = ByteWriter()
        writer.u32(prefix.size.toLong())
        writer.bytes(prefix)
        writer.bytes(tiff)
        val bytes = writer.toByteArray()
        // Readers stop at the end of the TIFF structure, so zero padding keeps the item's reserved size.
        return Payload(bytes) { capacity -> if (bytes.size <= capacity) bytes.copyOf(capacity) else null }
    }

    private fun hasIccProfile(meta: HeifMeta): Boolean = meta.properties.any { property ->
        property.size >= 12 && BoxBytes.fourCc(property, 4) == "colr" && BoxBytes.fourCc(property, 8).let { it == "prof" || it == "rICC" }
    }

    /** A box header with an explicit size, for a box that ran to the end of the file and now has data after it. */
    private fun explicitHeader(original: ByteArray, box: TopBox): ByteArray {
        val userType = original.copyOfRange(if (BoxBytes.u32(original, 0) == 1L) 16 else 8, original.size)
        val writer = ByteWriter()
        if (box.size <= 0xFFFFFFFFL) {
            writer.u32(box.size)
            writer.fourCc(box.type)
        } else {
            writer.u32(1)
            writer.fourCc(box.type)
            writer.u64(box.size + 8)
        }
        writer.bytes(userType)
        return writer.toByteArray()
    }

    private fun freeBox(size: Long): ByteArray = BoxBytes.box("free", ByteArray((size - 8).toInt()))

    private companion object {
        val EXIF_PREFIX = "Exif\u0000\u0000".latin1()
        val STRUCTURE_BOXES = setOf("ftyp", "meta", "moov")
        val FREE_BOXES = setOf("free", "skip")
        const val MAX_PAYLOAD = 64 * 1024 * 1024
        const val MAX_PREFIX = 64
        const val IDAT_LIMIT = 4096
        const val MAX_PASSES = 8
        const val MDAT_SLACK = 512

        fun roundUp(value: Int, step: Int): Int = (value + step - 1) / step * step

        fun overlaps(range: LongArray, start: Long, length: Long): Boolean = range[0] < start + length && start < range[0] + range[1]

        /** Sorted, merged (start, length) ranges. */
        fun merge(ranges: List<LongArray>): List<LongArray> {
            val result = mutableListOf<LongArray>()
            for (range in ranges.filter { it[1] > 0 }.sortedBy { it[0] }) {
                val last = result.lastOrNull()
                if (last != null && range[0] <= last[0] + last[1]) {
                    last[1] = maxOf(last[1], range[0] + range[1] - last[0])
                } else {
                    result += longArrayOf(range[0], range[1])
                }
            }
            return result
        }

        /** The parts of merged [ranges] not covered by merged [holes]. */
        fun subtract(ranges: List<LongArray>, holes: List<LongArray>): List<LongArray> {
            val result = mutableListOf<LongArray>()
            for (range in ranges) {
                var start = range[0]
                val end = range[0] + range[1]
                for (hole in holes) {
                    val holeEnd = hole[0] + hole[1]
                    if (holeEnd <= start || hole[0] >= end) continue
                    if (hole[0] > start) result += longArrayOf(start, hole[0] - start)
                    start = maxOf(start, holeEnd)
                }
                if (end > start) result += longArrayOf(start, end - start)
            }
            return result
        }
    }
}
