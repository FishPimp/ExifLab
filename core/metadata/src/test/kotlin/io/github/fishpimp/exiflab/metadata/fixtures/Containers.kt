package io.github.fishpimp.exiflab.metadata.fixtures

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import javax.imageio.ImageIO

/** Builders for the image containers the engine reads. Everything is generated in code. */
object Containers {
    /** A real baseline JPEG of [width] x [height] pixels, encoded by ImageIO. */
    fun jpeg(width: Int = 64, height: Int = 48): ByteArray = encode(width, height, "jpg")

    /** A real PNG of [width] x [height] pixels, encoded by ImageIO. */
    fun png(width: Int = 32, height: Int = 24): ByteArray = encode(width, height, "png")

    /** Inserts marker segments (type to payload) right after the SOI marker of [jpeg]. */
    fun jpegWithSegments(jpeg: ByteArray, vararg segments: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(jpeg, 0, 2)
        for ((marker, payload) in segments) {
            require(payload.size + 2 <= 0xFFFF) { "Segment too large" }
            out.write(0xFF)
            out.write(marker)
            out.write(be16(payload.size + 2))
            out.write(payload)
        }
        out.write(jpeg, 2, jpeg.size - 2)
        return out.toByteArray()
    }

    fun exifSegment(tiff: ByteArray): Pair<Int, ByteArray> = 0xE1 to ("Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff)

    fun xmpSegment(xmp: String): Pair<Int, ByteArray> =
        0xE1 to ("http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) + xmp.toByteArray(Charsets.UTF_8))

    /** APP13 Photoshop segment with an IPTC-NAA resource (0x0404) holding [records] (dataset to text). */
    fun iptcSegment(vararg records: Pair<Int, String>): Pair<Int, ByteArray> {
        val iptc = ByteArrayOutputStream()
        for ((dataset, text) in records) {
            val value = text.toByteArray(Charsets.UTF_8)
            iptc.write(0x1C)
            iptc.write(2)
            iptc.write(dataset)
            iptc.write(be16(value.size))
            iptc.write(value)
        }
        val data = iptc.toByteArray()
        val resource = ByteArrayOutputStream()
        resource.write("8BIM".toByteArray(Charsets.ISO_8859_1))
        resource.write(be16(0x0404))
        resource.write(byteArrayOf(0, 0))
        resource.write(be32(data.size))
        resource.write(data)
        if (data.size % 2 == 1) resource.write(0)
        return 0xED to ("Photoshop 3.0\u0000".toByteArray(Charsets.ISO_8859_1) + resource.toByteArray())
    }

    /** APP2 ICC_PROFILE segment carrying [profile] in one chunk. */
    fun iccSegment(profile: ByteArray): Pair<Int, ByteArray> =
        0xE2 to ("ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(1, 1) + profile)

    /** A PNG chunk with a correct CRC. */
    fun pngChunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply { update(typeBytes); update(data) }.value
        return be32(data.size) + typeBytes + data + be32(crc.toInt())
    }

    /** Inserts [chunks] right after the IHDR chunk of [png]. */
    fun pngWithChunks(png: ByteArray, vararg chunks: ByteArray): ByteArray {
        val ihdrEnd = 8 + 8 + 13 + 4
        return png.copyOfRange(0, ihdrEnd) + chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk } + png.copyOfRange(ihdrEnd, png.size)
    }

    fun pngXmpChunk(xmp: String): ByteArray = pngChunk(
        "iTXt",
        "XML:com.adobe.xmp".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 0, 0, 0, 0) + xmp.toByteArray(Charsets.UTF_8),
    )

    fun pngTextChunk(keyword: String, text: String): ByteArray =
        pngChunk("tEXt", keyword.toByteArray(Charsets.ISO_8859_1) + 0.toByte() + text.toByteArray(Charsets.ISO_8859_1))

    /**
     * An extended-format WebP: VP8X (with Exif and XMP flags), a 1 x 1 lossless VP8L image and
     * the optional EXIF and XMP chunks. The canvas size in VP8X is [width] x [height].
     */
    fun webp(width: Int, height: Int, exif: ByteArray? = null, xmp: String? = null): ByteArray {
        val flags = (if (exif != null) 0x08 else 0) or (if (xmp != null) 0x04 else 0)
        val vp8x = byteArrayOf(flags.toByte(), 0, 0, 0) + le24(width - 1) + le24(height - 1)
        val vp8l = byteArrayOf(0x2F, 0x00, 0x00, 0x00, 0x10, 0x07, 0x10, 0x11, 0x11, 0x88.toByte(), 0x88.toByte(), 0xFE.toByte(), 0x07, 0x00)
        val chunks = riffChunk("VP8X", vp8x) + riffChunk("VP8L", vp8l) +
            (exif?.let { riffChunk("EXIF", it) } ?: ByteArray(0)) +
            (xmp?.let { riffChunk("XMP ", it.toByteArray(Charsets.UTF_8)) } ?: ByteArray(0))
        return "RIFF".toByteArray(Charsets.ISO_8859_1) + le32(chunks.size + 4) + "WEBP".toByteArray(Charsets.ISO_8859_1) + chunks
    }

    /**
     * A Fujifilm RAF: the 108-byte header, a tag directory with raw image sizes stored as
     * (height, width), and the embedded [jpeg].
     */
    fun raf(jpeg: ByteArray, model: String = "X-T3", fullSize: Pair<Int, Int>? = 6384 to 4182, croppedSize: Pair<Int, Int>? = 6240 to 4160): ByteArray {
        val directory = ByteArrayOutputStream()
        val records = listOfNotNull(fullSize?.let { 0x100 to it }, croppedSize?.let { 0x111 to it })
        directory.write(be32(records.size))
        for ((tag, size) in records) {
            directory.write(be16(tag))
            directory.write(be16(4))
            directory.write(be16(size.second))
            directory.write(be16(size.first))
        }
        val dirBytes = directory.toByteArray()
        val headerSize = 160
        val dirOffset = headerSize
        val jpegOffset = dirOffset + dirBytes.size
        val header = ByteArray(headerSize)
        "FUJIFILMCCD-RAW 0201FF129502".toByteArray(Charsets.ISO_8859_1).copyInto(header, 0)
        model.toByteArray(Charsets.ISO_8859_1).copyInto(header, 28)
        "0100".toByteArray(Charsets.ISO_8859_1).copyInto(header, 60)
        be32(jpegOffset).copyInto(header, 84)
        be32(jpeg.size).copyInto(header, 88)
        be32(dirOffset).copyInto(header, 92)
        be32(dirBytes.size).copyInto(header, 96)
        return header + dirBytes + jpeg + ByteArray(64)
    }

    /** An ISO-BMFF box. */
    fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { acc, part -> acc + part }
        return be32(body.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + body
    }

    /** An ISO-BMFF `uuid` box with the given extended type. */
    fun uuidBox(uuid: String, vararg payload: ByteArray): ByteArray = box("uuid", hex(uuid.replace("-", "")), *payload)

    /** A FullBox payload prefix: version and 24-bit flags. */
    fun fullBoxHeader(version: Int = 0, flags: Int = 0): ByteArray = byteArrayOf(version.toByte()) + be32(flags).copyOfRange(1, 4)

    fun encode(width: Int, height: Int, format: String): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, (x * 255 / width shl 16) or (y * 255 / height shl 8) or 0x40)
        return ByteArrayOutputStream().also { check(ImageIO.write(image, format, it)) { "No ImageIO writer for $format" } }.toByteArray()
    }

    fun be16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())
    fun be32(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
    fun le32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun le24(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte())

    private fun riffChunk(fourCc: String, data: ByteArray): ByteArray =
        fourCc.toByteArray(Charsets.ISO_8859_1) + le32(data.size) + data + if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0)

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** Builds an XMP packet from raw RDF description content. */
object Xmp {
    fun packet(vararg descriptions: String): String = buildString {
        append("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
        for (description in descriptions) append(description).append('\n')
        append("</rdf:RDF></x:xmpmeta>\n<?xpacket end=\"w\"?>")
    }

    const val NAMESPACES = """xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:xmp="http://ns.adobe.com/xap/1.0/"
        xmlns:xmpMM="http://ns.adobe.com/xap/1.0/mm/" xmlns:exif="http://ns.adobe.com/exif/1.0/"
        xmlns:exifEX="http://cipa.jp/exif/1.0/" xmlns:aux="http://ns.adobe.com/exif/1.0/aux/"
        xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/" xmlns:tiff="http://ns.adobe.com/tiff/1.0/"
        xmlns:mwg-rs="http://www.metadataworkinggroup.com/schemas/regions/"
        xmlns:stArea="http://ns.adobe.com/xmp/sType/Area#" xmlns:stDim="http://ns.adobe.com/xap/1.0/sType/Dimensions#"
        xmlns:xmpRights="http://ns.adobe.com/xap/1.0/rights/""""

    /** An rdf:Description with [NAMESPACES], simple [attributes] and nested [elements] XML. */
    fun description(attributes: Map<String, String> = emptyMap(), elements: String = ""): String =
        "<rdf:Description rdf:about=\"\" $NAMESPACES " +
            attributes.entries.joinToString(" ") { (name, value) -> "$name=\"$value\"" } +
            ">$elements</rdf:Description>"
}
