package io.github.fishpimp.exiflab.metadata.preview

import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifReader
import io.github.fishpimp.exiflab.metadata.container.Bmff
import io.github.fishpimp.exiflab.metadata.container.BmffBox
import io.github.fishpimp.exiflab.metadata.container.Cr3Reader
import io.github.fishpimp.exiflab.metadata.container.JpegSegments
import io.github.fishpimp.exiflab.metadata.container.RafReader
import io.github.fishpimp.exiflab.metadata.io.SeekableSource
import io.github.fishpimp.exiflab.metadata.io.indexOfByte
import io.github.fishpimp.exiflab.metadata.io.u32
import io.github.fishpimp.exiflab.metadata.io.u8

/** Preview locations in the non-TIFF RAW containers. */
internal object ContainerPreviews {
    /** RAF: the header points at the embedded JPEG, whose own Exif holds the orientation. */
    fun raf(source: SeekableSource): PreviewScan {
        val header = RafReader.header(source) ?: return PreviewScan.EMPTY
        if (header.jpegOffset <= 0 || header.jpegLength <= 0) return PreviewScan.EMPTY
        val exif = Metadata()
        JpegSegments.read(source, header.jpegOffset, exif, mutableListOf(), readers = listOf(ExifReader()))
        val orientation = exif.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            ?.getInteger(ExifIFD0Directory.TAG_ORIENTATION)
            ?.takeIf { it in 1..8 }
        return PreviewScan(listOf(PreviewCandidate(header.jpegOffset, header.jpegLength)), orientation)
    }

    /**
     * CR3: the PRVW box inside `uuid(eaf42b5e-...)` (about 1620 x 1080) and the THMB box in the
     * Canon metadata box (160 x 120); orientation comes from the CMT1 block (IFD0).
     */
    fun cr3(source: SeekableSource): PreviewScan {
        val topLevel = Bmff.boxes(source, 0, source.length)
        val candidates = mutableListOf<PreviewCandidate>()
        topLevel.firstOrNull { it.type == "uuid" && it.userType == Cr3Reader.PREVIEW_UUID }?.let { uuid ->
            // The preview uuid box has 8 bytes of its own before the PRVW box.
            Bmff.boxes(source, uuid.payloadStart + 8, uuid.end).firstOrNull { it.type == "PRVW" }
                ?.let { jpegInBox(source, it, sizeAt = 12, dataAt = 16) }
                ?.let(candidates::add)
        }
        val canon = Cr3Reader.canonBox(source, topLevel)
        val canonChildren = canon?.let { Bmff.children(source, it) }.orEmpty()
        canonChildren.firstOrNull { it.type == "THMB" }?.let { jpegInBox(source, it, sizeAt = 8, dataAt = 16) }?.let(candidates::add)
        val orientation = canonChildren.firstOrNull { it.type == "CMT1" }
            ?.let { TiffPreviewScanner(source, it.payloadStart).scan().orientation }
        return PreviewScan(candidates, orientation)
    }

    /**
     * The JPEG inside a CR3 preview box: a 32-bit size at [sizeAt] and the data at [dataAt]
     * (payload-relative). When the layout differs, the first SOI marker near the start is used
     * and the JPEG is assumed to run to the end of the box.
     */
    private fun jpegInBox(source: SeekableSource, box: BmffBox, sizeAt: Int, dataAt: Int): PreviewCandidate? {
        val head = source.readUpTo(box.payloadStart, 64)
        val payloadSize = box.payloadSize
        if (head.size > dataAt + 1 && head.u8(dataAt) == 0xFF && head.u8(dataAt + 1) == 0xD8) {
            val size = head.u32(sizeAt, bigEndian = true)
            if (size in 3..payloadSize - dataAt) return PreviewCandidate(box.payloadStart + dataAt, size)
        }
        var at = head.indexOfByte(0xFF)
        while (at >= 0 && at + 1 < head.size) {
            if (head.u8(at + 1) == 0xD8) return PreviewCandidate(box.payloadStart + at, payloadSize - at)
            at = head.indexOfByte(0xFF, at + 1)
        }
        return null
    }
}
