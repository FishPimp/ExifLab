package io.github.fishpimp.exiflab.data.batch

import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.write.ExifIfd
import io.github.fishpimp.exiflab.metadata.write.WriteSkip
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Stores the [WriteSkip]s of a batch photo as JSON in [BatchJobItem.notes], so the summary words
 * them in the language shown at the time. The English detail of [WriteSkip.ExifDataDropped] is
 * not kept. Unknown kinds and unreadable JSON are left out, so old rows never break the summary.
 */
object WriteSkipCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(StoredSkip.serializer())

    /** The JSON of [skips], or null when there are none. */
    fun encode(skips: List<WriteSkip>): String? =
        skips.takeIf { it.isNotEmpty() }?.let { list -> json.encodeToString(serializer, list.map(::store)) }

    fun decode(text: String?): List<WriteSkip> {
        if (text.isNullOrBlank()) return emptyList()
        val stored = runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList())
        return stored.mapNotNull(::restore)
    }

    private fun store(skip: WriteSkip): StoredSkip = when (skip) {
        is WriteSkip.IptcUnsupported -> StoredSkip(IPTC_UNSUPPORTED, format = skip.container.name, count = skip.count)
        WriteSkip.HeifColorProfileKept -> StoredSkip(HEIF_COLOR_PROFILE)
        is WriteSkip.StructuralTag -> StoredSkip(STRUCTURAL_TAG, ifd = skip.ifd.name, tag = skip.tag)
        is WriteSkip.IptcDatasetRejected -> StoredSkip(IPTC_REJECTED, record = skip.record, dataset = skip.dataset, tooLong = skip.tooLong)
        WriteSkip.MakerNoteMoved -> StoredSkip(MAKER_NOTE_MOVED)
        is WriteSkip.ExifDataDropped -> StoredSkip(EXIF_DROPPED)
    }

    private fun restore(stored: StoredSkip): WriteSkip? = when (stored.kind) {
        IPTC_UNSUPPORTED -> WriteSkip.IptcUnsupported(
            container = ImageFormat.entries.firstOrNull { it.name == stored.format } ?: ImageFormat.Unknown,
            count = stored.count ?: 1,
        )
        HEIF_COLOR_PROFILE -> WriteSkip.HeifColorProfileKept
        STRUCTURAL_TAG -> stored.tag?.let { tag ->
            WriteSkip.StructuralTag(ExifIfd.entries.firstOrNull { it.name == stored.ifd } ?: ExifIfd.Primary, tag)
        }
        IPTC_REJECTED -> if (stored.record != null && stored.dataset != null) {
            WriteSkip.IptcDatasetRejected(stored.record, stored.dataset, stored.tooLong == true)
        } else {
            null
        }
        MAKER_NOTE_MOVED -> WriteSkip.MakerNoteMoved
        EXIF_DROPPED -> WriteSkip.ExifDataDropped(detail = "")
        else -> null
    }

    private const val IPTC_UNSUPPORTED = "iptcUnsupported"
    private const val HEIF_COLOR_PROFILE = "heifColorProfileKept"
    private const val STRUCTURAL_TAG = "structuralTag"
    private const val IPTC_REJECTED = "iptcDatasetRejected"
    private const val MAKER_NOTE_MOVED = "makerNoteMoved"
    private const val EXIF_DROPPED = "exifDataDropped"

    @Serializable
    private data class StoredSkip(
        val kind: String,
        val format: String? = null,
        val count: Int? = null,
        val ifd: String? = null,
        val tag: Int? = null,
        val record: Int? = null,
        val dataset: Int? = null,
        val tooLong: Boolean? = null,
    )
}
