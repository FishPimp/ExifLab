package io.github.fishpimp.exiflab.data.history

import io.github.fishpimp.exiflab.metadata.edit.FieldDiff
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Stores a list of [FieldDiff] as JSON in [EditRecord.diffJson]. The metadata module has no
 * serialization, so a private mirror class carries the fields; unknown groups read as
 * [DirectoryGroup.Other] and unreadable JSON as an empty list, so old rows never break History.
 */
object FieldDiffCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(StoredFieldDiff.serializer())

    fun encode(diff: List<FieldDiff>): String =
        json.encodeToString(serializer, diff.map { StoredFieldDiff(it.label, it.group.name, it.before, it.after) })

    fun decode(text: String): List<FieldDiff> =
        runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList()).map { stored ->
            FieldDiff(
                label = stored.label,
                group = DirectoryGroup.entries.firstOrNull { it.name == stored.group } ?: DirectoryGroup.Other,
                before = stored.before,
                after = stored.after,
            )
        }

    /** The diff of undoing [diff]: every value goes back from "after" to "before". */
    fun invert(diff: List<FieldDiff>): List<FieldDiff> = diff.map { it.copy(before = it.after, after = it.before) }

    @Serializable
    private data class StoredFieldDiff(
        val label: String,
        val group: String,
        val before: String? = null,
        val after: String? = null,
    )
}
