package io.github.fishpimp.exiflab.metadata.write

import java.nio.ByteBuffer

/** Minimal JPEG segment listing for assertions on the writer's output. */
object JpegStructure {
    class Segment(val marker: Int, val offset: Int, val payload: ByteArray) {
        fun startsWith(text: String) = String(payload, 0, minOf(payload.size, text.length), Charsets.ISO_8859_1) == text
        val label: String get() = when {
            marker == 0xE0 -> "APP0"
            marker == 0xE1 && startsWith("Exif") -> "Exif"
            marker == 0xE1 && startsWith("http://ns.adobe.com/xap/1.0/\u0000") -> "XMP"
            marker == 0xE1 && startsWith("http://ns.adobe.com/xmp/extension/\u0000") -> "ExtXMP"
            marker == 0xE2 && startsWith("ICC_PROFILE") -> "ICC"
            marker == 0xE2 && startsWith("MPF") -> "MPF"
            marker == 0xED -> "APP13"
            marker == 0xFE -> "COM"
            marker == 0xDB -> "DQT"
            marker == 0xC4 -> "DHT"
            marker in 0xC0..0xCF -> "SOF"
            else -> "%02X".format(marker)
        }
    }

    /** Segments between SOI and the first SOS. */
    fun segments(jpeg: ByteArray): List<Segment> {
        val result = mutableListOf<Segment>()
        var position = 2
        while (position + 4 <= jpeg.size) {
            check(jpeg[position].toInt() and 0xFF == 0xFF) { "Bad marker at $position" }
            val marker = jpeg[position + 1].toInt() and 0xFF
            if (marker == 0xDA) break
            val length = ((jpeg[position + 2].toInt() and 0xFF) shl 8) or (jpeg[position + 3].toInt() and 0xFF)
            result += Segment(marker, position, jpeg.copyOfRange(position + 4, position + 2 + length))
            position += 2 + length
        }
        return result
    }

    fun labels(jpeg: ByteArray): List<String> = segments(jpeg).map { it.label }

    /** Offset of the first SOS marker. */
    fun sosOffset(jpeg: ByteArray): Int {
        val last = segments(jpeg).last()
        return last.offset + 4 + last.payload.size
    }

    fun exifTiff(jpeg: ByteArray): ByteArray = segments(jpeg).first { it.label == "Exif" }.payload.let { it.copyOfRange(6, it.size) }

    fun be32(bytes: ByteArray, at: Int): Long = ByteBuffer.wrap(bytes, at, 4).int.toLong() and 0xFFFFFFFFL
}
