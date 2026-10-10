package io.github.fishpimp.exiflab.metadata.write.digest

import io.github.fishpimp.exiflab.metadata.ImageFormat
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Streaming SHA-256 over the image-carrying parts of a container. Bytes are pushed in as they
 * are read or written, so the same code digests a source file and the output of a writer
 * without either being held in memory. A structure the sink does not expect switches it to
 * hashing everything that follows, which can only make a comparison stricter.
 */
internal abstract class ImageDigestSink : OutputStream() {
    protected val sha: MessageDigest = MessageDigest.getInstance("SHA-256")
    private val single = ByteArray(1)

    /** Finishes the digest and returns it as lowercase hex. */
    fun hex(): String = sha.digest().joinToString("") { "%02x".format(it) }

    override fun write(b: Int) {
        single[0] = b.toByte()
        write(single, 0, 1)
    }

    abstract override fun write(b: ByteArray, off: Int, len: Int)

    /** Collects a fixed number of header bytes across write calls. */
    protected class Collector(size: Int) {
        val bytes = ByteArray(size)
        var filled = 0
            private set

        /** Takes bytes from [b]; returns how many were consumed. */
        fun take(b: ByteArray, off: Int, len: Int): Int {
            val n = minOf(len, bytes.size - filled)
            b.copyInto(bytes, filled, off, off + n)
            filled += n
            return n
        }

        val full: Boolean get() = filled == bytes.size

        fun reset() {
            filled = 0
        }
    }

    companion object {
        fun forFormat(format: ImageFormat): ImageDigestSink? = when (format) {
            ImageFormat.Jpeg -> JpegDigestSink()
            ImageFormat.Png -> PngDigestSink()
            ImageFormat.WebP -> WebpDigestSink()
            else -> null
        }
    }
}

/**
 * JPEG: quantization and Huffman tables, frame headers (SOFn), restart interval, arithmetic
 * conditioning, the Adobe APP14 color transform, and everything from the first SOS marker to the
 * end of the file (entropy-coded data, later scans, the EOI marker and any bytes after it such as
 * Motion Photo video, Ultra HDR gain maps and MPF secondary images).
 */
internal class JpegDigestSink : ImageDigestSink() {
    private enum class State { Soi, Marker, Length, Payload, Raw }

    private var state = State.Soi
    private val soi = Collector(2)
    private val length = Collector(2)
    private var marker = 0
    private var sawFf = false
    private var remaining = 0
    private var hashing = false

    override fun write(b: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        while (i < end) {
            when (state) {
                State.Raw -> {
                    sha.update(b, i, end - i)
                    i = end
                }
                State.Soi -> {
                    i += soi.take(b, i, end - i)
                    if (soi.full) {
                        if ((soi.bytes[0].toInt() and 0xFF) == 0xFF && (soi.bytes[1].toInt() and 0xFF) == 0xD8) {
                            state = State.Marker
                        } else {
                            sha.update(soi.bytes)
                            state = State.Raw
                        }
                    }
                }
                State.Marker -> {
                    val c = b[i++].toInt() and 0xFF
                    if (!sawFf) {
                        if (c == 0xFF) {
                            sawFf = true
                        } else {
                            sha.update(c.toByte())
                            state = State.Raw
                        }
                    } else if (c != 0xFF) {
                        sawFf = false
                        marker = c
                        when {
                            c == SOS || c == EOI -> {
                                sha.update(0xFF.toByte())
                                sha.update(c.toByte())
                                state = State.Raw
                            }
                            c == TEM || c in RST0..RST7 || c == 0x00 -> {
                                sha.update(0xFF.toByte())
                                sha.update(c.toByte())
                            }
                            else -> {
                                length.reset()
                                state = State.Length
                            }
                        }
                    }
                }
                State.Length -> {
                    i += length.take(b, i, end - i)
                    if (length.full) {
                        val value = ((length.bytes[0].toInt() and 0xFF) shl 8) or (length.bytes[1].toInt() and 0xFF)
                        hashing = marker in IMAGE_MARKERS
                        if (value < 2) {
                            sha.update(byteArrayOf(0xFF.toByte(), marker.toByte()) + length.bytes)
                            state = State.Raw
                        } else {
                            if (hashing) sha.update(byteArrayOf(0xFF.toByte(), marker.toByte()) + length.bytes)
                            remaining = value - 2
                            state = if (remaining == 0) State.Marker else State.Payload
                        }
                    }
                }
                State.Payload -> {
                    val n = minOf(remaining, end - i)
                    if (hashing) sha.update(b, i, n)
                    i += n
                    remaining -= n
                    if (remaining == 0) state = State.Marker
                }
            }
        }
    }

    private companion object {
        const val SOS = 0xDA
        const val EOI = 0xD9
        const val TEM = 0x01
        const val RST0 = 0xD0
        const val RST7 = 0xD7

        /** SOF0-SOF15 (without DHT 0xC4, JPG 0xC8, DAC 0xCC listed separately), DHT, DAC, DQT, DNL, DRI, DHP, EXP, APP14. */
        val IMAGE_MARKERS: Set<Int> = (0xC0..0xCF).toSet() + setOf(0xDB, 0xDC, 0xDD, 0xDE, 0xDF, 0xEE)
    }
}

/**
 * PNG: the signature, critical chunks (IHDR, PLTE, IDAT, IEND and any other uppercase-initial
 * chunk), transparency (tRNS), APNG animation chunks (acTL, fcTL, fdAT) and any bytes after IEND.
 */
internal class PngDigestSink : ImageDigestSink() {
    private enum class State { Signature, Header, Body, Raw }

    private var state = State.Signature
    private val signature = Collector(8)
    private val header = Collector(8)
    private var remaining = 0L
    private var hashing = false
    private var ended = false

    override fun write(b: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        while (i < end) {
            when (state) {
                State.Raw -> {
                    sha.update(b, i, end - i)
                    i = end
                }
                State.Signature -> {
                    i += signature.take(b, i, end - i)
                    if (signature.full) {
                        sha.update(signature.bytes)
                        state = if (signature.bytes.contentEquals(PNG_SIGNATURE)) State.Header else State.Raw
                    }
                }
                State.Header -> {
                    i += header.take(b, i, end - i)
                    if (header.full) {
                        val length = ((header.bytes[0].toLong() and 0xFF) shl 24) or ((header.bytes[1].toLong() and 0xFF) shl 16) or
                            ((header.bytes[2].toLong() and 0xFF) shl 8) or (header.bytes[3].toLong() and 0xFF)
                        val type = String(header.bytes, 4, 4, Charsets.ISO_8859_1)
                        if (length > Int.MAX_VALUE) {
                            sha.update(header.bytes)
                            state = State.Raw
                        } else {
                            hashing = isImageChunk(type)
                            if (hashing) sha.update(header.bytes)
                            ended = type == "IEND"
                            remaining = length + CRC_SIZE
                            state = State.Body
                        }
                        header.reset()
                    }
                }
                State.Body -> {
                    val n = minOf(remaining, (end - i).toLong()).toInt()
                    if (hashing) sha.update(b, i, n)
                    i += n
                    remaining -= n
                    if (remaining == 0L) state = if (ended) State.Raw else State.Header
                }
            }
        }
    }

    companion object {
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private const val CRC_SIZE = 4L
        private val IMAGE_ANCILLARY = setOf("tRNS", "acTL", "fcTL", "fdAT")

        fun isImageChunk(type: String): Boolean = type[0].isUpperCase() || type in IMAGE_ANCILLARY
    }
}

/**
 * WebP: the image chunks VP8, VP8L, ALPH, ANIM and ANMF (header and data, not the pad byte) and
 * any bytes after the RIFF payload. The RIFF size and the VP8X header (feature flags) are
 * metadata here.
 */
internal class WebpDigestSink : ImageDigestSink() {
    private enum class State { Riff, Header, Body, Raw }

    private var state = State.Riff
    private val riff = Collector(12)
    private val header = Collector(8)
    private var position = 0L
    private var riffEnd = 0L
    private var remaining = 0L
    private var pad = 0L
    private var hashing = false

    override fun write(b: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        while (i < end) {
            if (state == State.Header && position >= riffEnd) state = State.Raw
            when (state) {
                State.Raw -> {
                    sha.update(b, i, end - i)
                    position += end - i
                    i = end
                }
                State.Riff -> {
                    val n = riff.take(b, i, end - i)
                    i += n
                    position += n
                    if (riff.full) {
                        val text = String(riff.bytes, Charsets.ISO_8859_1)
                        if (text.startsWith("RIFF") && text.endsWith("WEBP")) {
                            riffEnd = 8 + le32(riff.bytes, 4)
                            state = State.Header
                        } else {
                            sha.update(riff.bytes)
                            state = State.Raw
                        }
                    }
                }
                State.Header -> {
                    val n = header.take(b, i, end - i)
                    i += n
                    position += n
                    if (header.full) {
                        val fourCc = String(header.bytes, 0, 4, Charsets.ISO_8859_1)
                        val size = le32(header.bytes, 4)
                        hashing = fourCc in IMAGE_CHUNKS
                        if (hashing) sha.update(header.bytes)
                        header.reset()
                        remaining = size
                        pad = size and 1L
                        state = if (remaining + pad == 0L) State.Header else State.Body
                    }
                }
                State.Body -> {
                    if (remaining > 0) {
                        val n = minOf(remaining, (end - i).toLong()).toInt()
                        if (hashing) sha.update(b, i, n)
                        i += n
                        position += n
                        remaining -= n
                    } else {
                        // The pad byte is container structure, not image data.
                        i++
                        position++
                        pad = 0
                    }
                    if (remaining == 0L && pad == 0L) state = State.Header
                }
            }
        }
    }

    companion object {
        val IMAGE_CHUNKS = setOf("VP8 ", "VP8L", "ALPH", "ANIM", "ANMF")

        private fun le32(bytes: ByteArray, at: Int): Long =
            (bytes[at].toLong() and 0xFF) or ((bytes[at + 1].toLong() and 0xFF) shl 8) or
                ((bytes[at + 2].toLong() and 0xFF) shl 16) or ((bytes[at + 3].toLong() and 0xFF) shl 24)
    }
}
