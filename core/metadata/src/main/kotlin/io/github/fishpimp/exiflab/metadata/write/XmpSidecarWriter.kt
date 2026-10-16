package io.github.fishpimp.exiflab.metadata.write

import com.adobe.internal.xmp.XMPException
import io.github.fishpimp.exiflab.metadata.write.sidecar.ExifToXmp
import io.github.fishpimp.exiflab.metadata.write.sidecar.IptcToXmp
import io.github.fishpimp.exiflab.metadata.write.xmp.XmpPackets
import java.io.OutputStream

/**
 * Creates or updates an XMP sidecar. An existing sidecar is parsed with XMPCore and everything in
 * it that the changes do not address (Lightroom develop settings, darktable history, ratings,
 * labels) is kept. EXIF changes are mapped to their XMP equivalents ([ExifToXmp]), IPTC
 * application record changes to the XMP properties that replace them, and XMP changes are
 * applied last, so an explicit XMP change wins over a mapped one. Removing the XMP block starts
 * from an empty packet; other block removals have no meaning for a sidecar.
 *
 * @throws UnsupportedEditException when [existingSidecar] is not valid XMP.
 */
class XmpSidecarWriter : SidecarWriter {
    override fun write(existingSidecar: ByteArray?, changes: MetadataChanges, output: OutputStream) {
        val keep = existingSidecar != null && existingSidecar.any { !it.toInt().toChar().isWhitespace() }
        val meta = if (keep && MetadataBlock.Xmp !in changes.removeBlocks) XmpPackets.parse(existingSidecar!!) else XmpPackets.empty()
        try {
            ExifToXmp(meta).apply(changes.exif)
            IptcToXmp.apply(meta, changes.iptc)
        } catch (e: XMPException) {
            throw UnsupportedEditException("Cannot map the changes to XMP: ${e.message}")
        }
        XmpPackets.apply(meta, changes.xmp)
        output.write(XmpPackets.serializeSidecar(meta))
    }

    companion object {
        /** XMP properties that hold when a photo was taken, as [Pair]s of namespace and name. */
        private val CAPTURE_DATES = listOf(
            "http://ns.adobe.com/exif/1.0/" to "DateTimeOriginal",
            "http://ns.adobe.com/exif/1.0/" to "DateTimeDigitized",
            "http://ns.adobe.com/xap/1.0/" to "CreateDate",
            "http://ns.adobe.com/photoshop/1.0/" to "DateCreated",
        )

        /**
         * True when [sidecar] records a capture date. Such a date wins over the RAW file's in
         * other apps and may differ from it, so dates worked out from the RAW file's own
         * ([io.github.fishpimp.exiflab.metadata.edit.EditPlan.datesFromFile]) must not replace it.
         * An unreadable sidecar counts as recording one.
         */
        fun recordsCaptureDate(sidecar: ByteArray): Boolean {
            if (sidecar.all { it.toInt().toChar().isWhitespace() }) return false
            val meta = try {
                XmpPackets.parse(sidecar)
            } catch (_: Exception) {
                return true
            }
            return CAPTURE_DATES.any { (namespace, name) -> runCatching { meta.doesPropertyExist(namespace, name) }.getOrDefault(false) }
        }
    }
}
