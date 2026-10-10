package io.github.fishpimp.exiflab.metadata.write.iptc

import io.github.fishpimp.exiflab.metadata.write.IptcChange
import io.github.fishpimp.exiflab.metadata.write.MetadataBlock
import io.github.fishpimp.exiflab.metadata.write.MetadataChanges
import io.github.fishpimp.exiflab.metadata.write.UnsupportedEditException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** One IPTC-IIM dataset, kept as raw bytes. */
internal class IptcDataset(val record: Int, val dataset: Int, val data: ByteArray) {
    val key: Int get() = record * 256 + dataset
}

/** Reads, edits and writes IPTC-IIM data (the payload of Photoshop resource 0x0404). */
internal object IptcIim {
    fun affects(changes: MetadataChanges): Boolean =
        changes.iptc.isNotEmpty() || changes.removeBlocks.any { it == MetadataBlock.Iptc || it == MetadataBlock.PhotoshopResources }

    fun parse(bytes: ByteArray): MutableList<IptcDataset> {
        val datasets = mutableListOf<IptcDataset>()
        var position = 0
        while (position < bytes.size) {
            if (bytes[position].toInt() != MARKER) {
                // Writers pad the resource with zeros; anything else means the data is damaged.
                if ((position until bytes.size).all { bytes[it].toInt() == 0 }) break
                throw UnsupportedEditException("The IPTC data is damaged and cannot be edited safely")
            }
            if (position + 5 > bytes.size) throw UnsupportedEditException("The IPTC data ends early")
            val record = bytes[position + 1].toInt() and 0xFF
            val dataset = bytes[position + 2].toInt() and 0xFF
            var length = ((bytes[position + 3].toInt() and 0xFF) shl 8) or (bytes[position + 4].toInt() and 0xFF)
            position += 5
            if (length and 0x8000 != 0) {
                val size = length and 0x7FFF
                if (size > 4 || position + size > bytes.size) throw UnsupportedEditException("The IPTC data has an invalid length")
                length = 0
                repeat(size) { length = (length shl 8) or (bytes[position + it].toInt() and 0xFF) }
                position += size
            }
            if (length < 0 || position + length > bytes.size) throw UnsupportedEditException("The IPTC data ends early")
            datasets += IptcDataset(record, dataset, bytes.copyOfRange(position, position + length))
            position += length
        }
        return datasets
    }

    fun serialize(datasets: List<IptcDataset>): ByteArray {
        val out = ByteArrayOutputStream()
        for (item in datasets) {
            out.write(MARKER)
            out.write(item.record)
            out.write(item.dataset)
            if (item.data.size <= 0x7FFF) {
                out.write(item.data.size ushr 8)
                out.write(item.data.size)
            } else {
                out.write(0x80)
                out.write(4)
                out.write(ByteBuffer.allocate(4).putInt(item.data.size).array())
            }
            out.write(item.data)
        }
        return out.toByteArray()
    }

    /**
     * Applies [changes] to [datasets]. Replaced datasets keep the position of their first
     * occurrence; new ones are inserted in record/dataset order. Text is written as UTF-8,
     * declaring it in 1:90 (and converting existing Latin-1 text) when non-ASCII text is added.
     * Returns null when nothing but the record version and character set remain.
     */
    fun apply(datasets: MutableList<IptcDataset>, changes: List<IptcChange>, skipped: MutableList<String>): List<IptcDataset>? {
        val nonAscii = changes.any { change -> change is IptcChange.Set && change.values.any { value -> value.any { it.code > 0x7F } } }
        if (nonAscii) declareUtf8(datasets)
        for (change in changes) {
            val name = "IPTC %d:%03d".format(change.record, change.dataset)
            if (change.record !in 1..9 || change.dataset !in 0..255) {
                skipped += "$name is not a valid IPTC dataset"
                continue
            }
            val first = datasets.indexOfFirst { it.record == change.record && it.dataset == change.dataset }
            datasets.removeAll { it.record == change.record && it.dataset == change.dataset }
            if (change !is IptcChange.Set) continue
            val encoded = change.values.map { IptcDataset(change.record, change.dataset, it.toByteArray(Charsets.UTF_8)) }
            if (encoded.any { it.data.size > MAX_DATASET }) {
                skipped += "$name is longer than IPTC allows"
                continue
            }
            val key = change.record * 256 + change.dataset
            val at = if (first >= 0) first else datasets.indexOfFirst { it.key > key }.takeIf { it >= 0 } ?: datasets.size
            datasets.addAll(at, encoded)
        }
        if (datasets.any { it.record == 2 && it.dataset != 0 } && datasets.none { it.record == 2 && it.dataset == 0 }) {
            val at = datasets.indexOfFirst { it.record >= 2 }.takeIf { it >= 0 } ?: datasets.size
            datasets.add(at, IptcDataset(2, 0, byteArrayOf(0, 4)))
        }
        val meaningful = datasets.any { !(it.record == 2 && it.dataset == 0) && !(it.record == 1 && it.dataset == CODED_CHARACTER_SET) }
        return if (meaningful) datasets else null
    }

    /** Declares UTF-8 in 1:90, converting record 2 text that is not valid UTF-8 from Latin-1. */
    private fun declareUtf8(datasets: MutableList<IptcDataset>) {
        val declared = datasets.firstOrNull { it.record == 1 && it.dataset == CODED_CHARACTER_SET }
        if (declared != null && declared.data.contentEquals(UTF8_DECLARATION)) return
        for (i in datasets.indices) {
            val item = datasets[i]
            if (item.record == 2 && item.dataset !in BINARY_DATASETS && !isUtf8(item.data)) {
                datasets[i] = IptcDataset(item.record, item.dataset, String(item.data, Charsets.ISO_8859_1).toByteArray(Charsets.UTF_8))
            }
        }
        datasets.removeAll { it.record == 1 && it.dataset == CODED_CHARACTER_SET }
        val at = datasets.indexOfFirst { it.key > 1 * 256 + CODED_CHARACTER_SET }.takeIf { it >= 0 } ?: datasets.size
        datasets.add(at, IptcDataset(1, CODED_CHARACTER_SET, UTF8_DECLARATION))
    }

    private fun isUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    private const val MARKER = 0x1C
    private const val CODED_CHARACTER_SET = 90
    private const val MAX_DATASET = 0x7FFF
    private val UTF8_DECLARATION = byteArrayOf(0x1B, 0x25, 0x47)

    /** Record 2 datasets holding binary data: record version, audio and preview data. */
    private val BINARY_DATASETS = setOf(0, 150, 151, 152, 153, 154, 200, 201, 202)
}

/** One Photoshop image resource block ("8BIM" resource). */
internal class PhotoshopResource(val signature: ByteArray, val id: Int, val name: ByteArray, val data: ByteArray)

/** Reads and writes Photoshop image resources, as stored in JPEG APP13 segments. */
internal object PhotoshopResources {
    const val IPTC = 0x0404
    const val IPTC_DIGEST = 0x0425

    fun parse(bytes: ByteArray): MutableList<PhotoshopResource> {
        val resources = mutableListOf<PhotoshopResource>()
        var position = 0
        fun fail(): Nothing = throw UnsupportedEditException("The Photoshop resources (APP13) are damaged and cannot be edited safely")
        while (position < bytes.size) {
            if (position + 4 > bytes.size) {
                if ((position until bytes.size).all { bytes[it].toInt() == 0 }) break
                fail()
            }
            val signature = bytes.copyOfRange(position, position + 4)
            val text = String(signature, Charsets.ISO_8859_1)
            if (text !in SIGNATURES) {
                if ((position until bytes.size).all { bytes[it].toInt() == 0 }) break
                fail()
            }
            if (position + 7 > bytes.size) fail()
            val id = ((bytes[position + 4].toInt() and 0xFF) shl 8) or (bytes[position + 5].toInt() and 0xFF)
            val nameLength = bytes[position + 6].toInt() and 0xFF
            val nameSize = (1 + nameLength).let { it + (it and 1) }
            if (position + 6 + nameSize + 4 > bytes.size) fail()
            val name = bytes.copyOfRange(position + 6, position + 6 + nameSize)
            val sizeAt = position + 6 + nameSize
            val size = ByteBuffer.wrap(bytes, sizeAt, 4).int.toLong() and 0xFFFFFFFFL
            val dataAt = sizeAt + 4
            if (dataAt + size > bytes.size) fail()
            resources += PhotoshopResource(signature, id, name, bytes.copyOfRange(dataAt, (dataAt + size).toInt()))
            position = (dataAt + size + (size and 1)).toInt()
        }
        return resources
    }

    fun serialize(resources: List<PhotoshopResource>): ByteArray {
        val out = ByteArrayOutputStream()
        for (resource in resources) {
            out.write(resource.signature)
            out.write(resource.id ushr 8)
            out.write(resource.id)
            out.write(resource.name)
            out.write(ByteBuffer.allocate(4).putInt(resource.data.size).array())
            out.write(resource.data)
            if (resource.data.size % 2 == 1) out.write(0)
        }
        return out.toByteArray()
    }

    /**
     * Applies IPTC changes and removals to [resources]; returns null when no resource remains.
     * Only the IPTC resource (and the digest Photoshop keeps of it) is rewritten.
     */
    fun edit(resources: MutableList<PhotoshopResource>?, changes: MetadataChanges, skipped: MutableList<String>): List<PhotoshopResource>? {
        val list = if (MetadataBlock.PhotoshopResources in changes.removeBlocks || resources == null) mutableListOf() else resources
        val iptcIndex = list.indexOfFirst { it.id == IPTC && it.signature.contentEquals(SIGNATURE_8BIM) }
        val existing = if (iptcIndex >= 0 && MetadataBlock.Iptc !in changes.removeBlocks) IptcIim.parse(list[iptcIndex].data) else mutableListOf()
        val edited = if (changes.iptc.isEmpty() && existing.isEmpty()) null else IptcIim.apply(existing, changes.iptc, skipped)
        val iptcBytes = edited?.let(IptcIim::serialize)
        if (iptcBytes == null) {
            list.removeAll { it.id == IPTC || it.id == IPTC_DIGEST }
        } else {
            val resource = PhotoshopResource(SIGNATURE_8BIM, IPTC, EMPTY_NAME, iptcBytes)
            if (iptcIndex >= 0) list[iptcIndex] = resource else list += resource
            val digestIndex = list.indexOfFirst { it.id == IPTC_DIGEST }
            if (digestIndex >= 0) {
                val old = list[digestIndex]
                list[digestIndex] = PhotoshopResource(old.signature, old.id, old.name, MessageDigest.getInstance("MD5").digest(iptcBytes))
            }
        }
        return list.takeIf { it.isNotEmpty() }
    }

    private val SIGNATURES = setOf("8BIM", "PHUT", "AgHg", "DCSR", "MeSa")
    private val SIGNATURE_8BIM = "8BIM".toByteArray(Charsets.ISO_8859_1)
    private val EMPTY_NAME = byteArrayOf(0, 0)
}
