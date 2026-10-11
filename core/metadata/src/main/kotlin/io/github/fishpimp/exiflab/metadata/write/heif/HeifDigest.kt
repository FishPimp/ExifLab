package io.github.fishpimp.exiflab.metadata.write.heif

import io.github.fishpimp.exiflab.metadata.CorruptImageException
import io.github.fishpimp.exiflab.metadata.write.UnsupportedEditException
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * What the HEIF image data digest covers in one particular file: byte ranges of the file
 * ([starts], [lengths]; each is hashed on its own as the file streams past) and the order in
 * which their digests are combined with the parts known up front (item ids and types, `idat`
 * data, properties, references, sample descriptions).
 */
internal class HeifDigestPlan(val starts: LongArray, val lengths: LongArray, private val parts: List<Any>) {
    /** Combines the per-range digests ([fed] counts the bytes each range actually received). */
    fun finish(digests: List<ByteArray>, fed: LongArray): String {
        val sha = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            when (part) {
                is ByteArray -> sha.update(part)
                is Int -> {
                    sha.update(ByteBuffer.allocate(8).putLong(fed[part]).array())
                    sha.update(digests[part])
                }
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Plans the image data digest of a HEIF/AVIF file: SHA-256 over the data of every item that is
 * not Exif or XMP (in item id order, by its `iloc` extents), the properties in `ipco` and each
 * item's associations, the references between image items, entity groups and the samples of any
 * tracks. Metadata items, `cdsc` links to them and the box layout (offsets, sizes, padding) are
 * left out, so the digest stays the same across metadata edits that move data around.
 */
internal object HeifDigest {
    private const val VERSION = "ExifLab HEIF image data 1"

    private class Builder {
        val ranges = LinkedHashMap<Pair<Long, Long>, Int>()
        val parts = mutableListOf<Any>()

        fun bytes(data: ByteArray) {
            parts += data
        }

        fun long(value: Long) = bytes(ByteBuffer.allocate(8).putLong(value).array())

        fun text(value: String) {
            val data = value.toByteArray(Charsets.UTF_8)
            long(data.size.toLong())
            bytes(data)
        }

        fun hashed(data: ByteArray, from: Int = 0, to: Int = data.size) {
            long((to - from).toLong())
            bytes(MessageDigest.getInstance("SHA-256").apply { update(data, from, to - from) }.digest())
        }

        fun range(start: Long, length: Long) {
            long(length)
            parts += ranges.getOrPut(start to length) { ranges.size }
        }

        fun build(): HeifDigestPlan {
            val keys = ranges.keys.toList()
            return HeifDigestPlan(LongArray(keys.size) { keys[it].first }, LongArray(keys.size) { keys[it].second }, parts)
        }
    }

    fun plan(meta: HeifMeta, movie: Movie?, fileLength: Long): HeifDigestPlan {
        val builder = Builder()
        builder.text(VERSION)
        builder.long(meta.primaryId ?: -1L)
        val metadataIds = meta.infos.filter { it.isMetadata }.map { it.id }.toSet()
        val ids = (meta.infos.map { it.id } + meta.locations.entries.map { it.id }).toSortedSet() - metadataIds
        for (id in ids) {
            val info = meta.info(id)
            builder.long(id)
            builder.text(info?.type ?: "")
            builder.text(info?.contentType ?: "")
            builder.long(info?.protectionIndex?.toLong() ?: -1L)
            itemData(builder, meta, id, fileLength)
            for (box in meta.associations) {
                for ((_, associations) in box.entries.filter { it.first == id }) {
                    builder.long(associations.size.toLong())
                    for (association in associations) {
                        builder.long(if (association.essential) 1 else 0)
                        if (association.index == 0) {
                            builder.long(-1)
                        } else {
                            builder.hashed(meta.properties.getOrNull(association.index - 1) ?: BoxBytes.damaged("ipma"))
                        }
                    }
                }
            }
        }
        builder.long(meta.properties.size.toLong())
        for (property in meta.properties) builder.hashed(property)
        for (reference in meta.references?.references.orEmpty()) {
            if (reference.from in metadataIds) continue
            val to = reference.to.filter { it !in metadataIds }
            builder.text(reference.type)
            builder.long(reference.from)
            builder.long(to.size.toLong())
            to.forEach(builder::long)
        }
        meta.groups?.let(builder::hashed)
        for (track in movie?.tracks.orEmpty()) {
            track.description.forEach { builder.hashed(it) }
            builder.long(track.chunkOffsets.size.toLong())
            for (i in track.chunkOffsets.indices) {
                val start = track.chunkOffsets[i]
                val size = track.chunkSizes[i]
                if (size > 0 && (start < 0 || start + size > fileLength)) throw CorruptImageException("HEIF sample data runs past the end of the file; it may be truncated")
                builder.range(start, size)
            }
        }
        return builder.build()
    }

    private fun itemData(builder: Builder, meta: HeifMeta, id: Long, fileLength: Long) {
        val location = meta.locations[id]
        if (location == null) {
            builder.long(-1)
            return
        }
        if (location.dataReferenceIndex != 0) throw UnsupportedEditException("The HEIF file keeps item data in another file")
        builder.long(location.extents.size.toLong())
        when (location.constructionMethod) {
            0 -> for (extent in location.extents) {
                val start = location.baseOffset + extent.offset
                val length = if (extent.length == 0L) fileLength - start else extent.length
                if (start < 0 || length < 0 || start + length > fileLength) throw CorruptImageException("HEIF item data runs past the end of the file; it may be truncated")
                builder.range(start, length)
            }
            1 -> {
                val idat = meta.idat ?: BoxBytes.damaged("idat")
                for (extent in location.extents) {
                    val start = location.baseOffset + extent.offset
                    val length = if (extent.length == 0L) idat.size - start else extent.length
                    if (start < 0 || length < 0 || start + length > idat.size) BoxBytes.damaged("idat")
                    builder.hashed(idat, start.toInt(), (start + length).toInt())
                }
            }
            else -> throw UnsupportedEditException("The HEIF file builds items from other items (construction method ${location.constructionMethod}), which is not supported")
        }
    }
}
