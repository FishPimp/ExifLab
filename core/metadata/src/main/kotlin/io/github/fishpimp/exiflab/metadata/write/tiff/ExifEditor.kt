package io.github.fishpimp.exiflab.metadata.write.tiff

import io.github.fishpimp.exiflab.metadata.model.Rational
import io.github.fishpimp.exiflab.metadata.write.ExifChange
import io.github.fishpimp.exiflab.metadata.write.ExifIfd
import io.github.fishpimp.exiflab.metadata.write.ExifValue
import io.github.fishpimp.exiflab.metadata.write.MetadataBlock
import io.github.fishpimp.exiflab.metadata.write.MetadataChanges
import io.github.fishpimp.exiflab.metadata.write.UnsupportedEditException

/**
 * Applies the EXIF part of [MetadataChanges] to a parsed block. Block removals run first, then
 * the tag changes in order, so "remove EXIF, then set Orientation" yields a fresh minimal block.
 */
internal object ExifEditor {
    /** True when [changes] touch the EXIF block at all. */
    fun affects(changes: MetadataChanges): Boolean =
        changes.exif.isNotEmpty() || changes.removeBlocks.any { it in EXIF_BLOCKS }

    /**
     * Returns the edited document, or null when no EXIF block should remain. With [jpeg] facts,
     * IFDs created from nothing get the tags EXIF requires for JPEG (resolution, chroma
     * positioning, components, color space, pixel dimensions).
     */
    fun apply(original: TiffDocument?, changes: MetadataChanges, jpeg: JpegFacts?, skipped: MutableList<String>): TiffDocument? {
        var document = if (MetadataBlock.Exif in changes.removeBlocks) null else original
        if (MetadataBlock.MakerNote in changes.removeBlocks) document?.exif?.remove(Tiff.TAG_MAKER_NOTE)
        if (MetadataBlock.ExifThumbnail in changes.removeBlocks) document?.ifd0?.next = null

        val created = mutableSetOf<ExifIfd>()
        for (change in changes.exif) {
            val name = "EXIF tag 0x%04X (%s)".format(change.tag, change.ifd)
            when (change) {
                is ExifChange.Set -> {
                    if (change.tag in Tiff.STRUCTURAL_TAGS) {
                        skipped += "$name is part of the file structure and cannot be set directly"
                        continue
                    }
                    val target = document ?: newDocument(jpeg != null).also { document = it }
                    val ifd = resolve(target, change.ifd, created)
                    ifd.put(ExifValues.encode(change.tag, change.value, target.bigEndian, name))
                }
                is ExifChange.Remove -> {
                    val target = document ?: continue
                    // Structural tags can be removed: a pointer drops its sub-IFD, a thumbnail offset drops the data too.
                    find(target, change.ifd)?.remove(change.tag)
                }
            }
        }
        val result = document ?: return null
        addRequiredTags(result, created, jpeg)
        prune(result)
        return if (result.ifd0.isEmpty && result.ifd0.next == null) null else result
    }

    private fun newDocument(forJpeg: Boolean): TiffDocument {
        val document = TiffDocument(bigEndian = true, ifd0 = TiffIfd())
        if (forJpeg) {
            // Tags EXIF 2.3 requires in IFD0 of a JPEG: 72 dpi, inches, centered chroma samples.
            val ifd0 = document.ifd0
            ifd0.put(ExifValues.encode(0x011A, ExifValue.Rationals(listOf(RATIONAL_72)), true, "XResolution"))
            ifd0.put(ExifValues.encode(0x011B, ExifValue.Rationals(listOf(RATIONAL_72)), true, "YResolution"))
            ifd0.put(ExifValues.encode(0x0128, ExifValue.Shorts(listOf(2)), true, "ResolutionUnit"))
            ifd0.put(ExifValues.encode(0x0213, ExifValue.Shorts(listOf(1)), true, "YCbCrPositioning"))
        }
        return document
    }

    private fun find(document: TiffDocument, ifd: ExifIfd): TiffIfd? = when (ifd) {
        ExifIfd.Primary -> document.ifd0
        ExifIfd.Exif -> document.exif
        ExifIfd.Gps -> document.gps
        ExifIfd.Interoperability -> document.interop
        ExifIfd.Thumbnail -> document.thumbnail
    }

    private fun resolve(document: TiffDocument, ifd: ExifIfd, created: MutableSet<ExifIfd>): TiffIfd {
        find(document, ifd)?.let { return it }
        created += ifd
        val fresh = TiffIfd()
        when (ifd) {
            ExifIfd.Primary -> error("IFD0 always exists")
            ExifIfd.Exif -> document.ifd0.put(TiffEntry.SubIfds(Tiff.TAG_EXIF_IFD, Tiff.LONG, mutableListOf(fresh)))
            ExifIfd.Gps -> document.ifd0.put(TiffEntry.SubIfds(Tiff.TAG_GPS_IFD, Tiff.LONG, mutableListOf(fresh)))
            ExifIfd.Interoperability -> resolve(document, ExifIfd.Exif, created)
                .put(TiffEntry.SubIfds(Tiff.TAG_INTEROP_IFD, Tiff.LONG, mutableListOf(fresh)))
            ExifIfd.Thumbnail -> document.ifd0.next = fresh
        }
        return fresh
    }

    /** Tags a newly created sub-IFD must carry to be valid, unless the changes set them. */
    private fun addRequiredTags(document: TiffDocument, created: Set<ExifIfd>, jpeg: JpegFacts?) {
        val bigEndian = document.bigEndian
        if (ExifIfd.Exif in created) document.exif?.let { exif ->
            fun require(tag: Int, value: ExifValue, name: String) {
                if (exif[tag] == null) exif.put(ExifValues.encode(tag, value, bigEndian, name))
            }
            require(EXIF_VERSION, ExifValue.Undefined("0232".toByteArray()), "ExifVersion")
            if (jpeg != null) {
                require(0x9101, ExifValue.Undefined(byteArrayOf(1, 2, 3, 0)), "ComponentsConfiguration")
                require(0xA000, ExifValue.Undefined("0100".toByteArray()), "FlashpixVersion")
                // sRGB unless an ICC profile describes the colors ("uncalibrated").
                require(0xA001, ExifValue.Shorts(listOf(if (jpeg.hasIccProfile) 0xFFFF else 1)), "ColorSpace")
                if (jpeg.width > 0 && jpeg.height > 0) {
                    require(0xA002, ExifValue.Longs(listOf(jpeg.width.toLong())), "PixelXDimension")
                    require(0xA003, ExifValue.Longs(listOf(jpeg.height.toLong())), "PixelYDimension")
                }
            }
        }
        if (ExifIfd.Gps in created) document.gps?.let { gps ->
            if (gps[GPS_VERSION] == null) gps.put(ExifValues.encode(GPS_VERSION, ExifValue.Bytes(listOf(2, 3, 0, 0)), bigEndian, "GPSVersionID"))
        }
    }

    /** Drops sub-IFDs that ended up empty together with their pointers, innermost first. */
    private fun prune(document: TiffDocument) {
        document.exif?.let { exif -> if (exif.child(Tiff.TAG_INTEROP_IFD)?.isEmpty == true) exif.remove(Tiff.TAG_INTEROP_IFD) }
        val ifd0 = document.ifd0
        for (tag in listOf(Tiff.TAG_EXIF_IFD, Tiff.TAG_GPS_IFD)) if (ifd0.child(tag)?.isEmpty == true) ifd0.remove(tag)
        val thumbnail = ifd0.next
        if (thumbnail != null && thumbnail.isEmpty && thumbnail.next == null) ifd0.next = null
    }

    private val EXIF_BLOCKS = setOf(MetadataBlock.Exif, MetadataBlock.MakerNote, MetadataBlock.ExifThumbnail)
    private val RATIONAL_72 = Rational(72, 1)
    private const val EXIF_VERSION = 0x9000
    private const val GPS_VERSION = 0x0000
}

/** What a JPEG's frame header and segments say, for the tags a new Exif IFD of a JPEG requires. */
internal class JpegFacts(val width: Int, val height: Int, val hasIccProfile: Boolean)

/** Encodes [ExifValue]s as TIFF entries in a block's byte order. */
internal object ExifValues {
    fun encode(tag: Int, value: ExifValue, bigEndian: Boolean, name: String): TiffEntry.Value {
        val order = ByteOrder(bigEndian)
        fun invalid(reason: String): Nothing = throw UnsupportedEditException("$name: $reason")
        fun numbers(values: List<Long>, size: Int, range: LongRange): ByteArray {
            if (values.isEmpty()) invalid("needs at least one value")
            val bytes = ByteArray(values.size * size)
            values.forEachIndexed { i, v ->
                if (v !in range) invalid("value $v is out of range")
                when (size) {
                    1 -> bytes[i] = v.toByte()
                    2 -> order.put16(bytes, i * 2, v.toInt())
                    else -> order.put32(bytes, i * 4, v)
                }
            }
            return bytes
        }
        fun rationals(values: List<Rational>, range: LongRange): ByteArray =
            numbers(values.flatMap { listOf(it.numerator, it.denominator) }, 4, range)

        return when (value) {
            is ExifValue.Ascii -> {
                val bytes = value.text.toByteArray(Charsets.UTF_8) + 0.toByte()
                TiffEntry.Value(tag, Tiff.ASCII, bytes.size.toLong(), bytes)
            }
            is ExifValue.Bytes -> TiffEntry.Value(tag, Tiff.BYTE, value.values.size.toLong(), numbers(value.values.map { it.toLong() }, 1, 0L..0xFF))
            is ExifValue.Shorts -> TiffEntry.Value(tag, Tiff.SHORT, value.values.size.toLong(), numbers(value.values.map { it.toLong() }, 2, 0L..0xFFFF))
            is ExifValue.Longs -> TiffEntry.Value(tag, Tiff.LONG, value.values.size.toLong(), numbers(value.values, 4, 0L..0xFFFFFFFFL))
            is ExifValue.SignedLongs -> {
                val bytes = numbers(value.values.map { it.toLong() }, 4, Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                TiffEntry.Value(tag, Tiff.SLONG, value.values.size.toLong(), bytes)
            }
            is ExifValue.Rationals -> TiffEntry.Value(tag, Tiff.RATIONAL, value.values.size.toLong(), rationals(value.values, 0L..0xFFFFFFFFL))
            is ExifValue.SignedRationals -> TiffEntry.Value(
                tag, Tiff.SRATIONAL, value.values.size.toLong(),
                rationals(value.values, Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()),
            )
            is ExifValue.Undefined -> {
                if (value.bytes.isEmpty()) invalid("needs at least one byte")
                TiffEntry.Value(tag, Tiff.UNDEFINED, value.bytes.size.toLong(), value.bytes.copyOf())
            }
        }
    }
}
