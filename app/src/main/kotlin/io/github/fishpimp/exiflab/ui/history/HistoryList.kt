package io.github.fishpimp.exiflab.ui.history

import io.github.fishpimp.exiflab.data.history.EditBatch
import io.github.fishpimp.exiflab.data.history.EditRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the History list shows. */
data class HistoryUiState(
    val isLoading: Boolean = true,
    val days: List<HistoryDay> = emptyList(),
    /** "Today" in [zone], for the day headers. */
    val today: LocalDate = LocalDate.now(),
    val zone: ZoneId = ZoneId.systemDefault(),
    /** The batch whose originals are being restored right now, if any. */
    val restoringBatchId: String? = null,
) {
    val isEmpty: Boolean get() = !isLoading && days.isEmpty()
}

/** The edits of one calendar day, newest first. */
data class HistoryDay(val date: LocalDate, val items: List<HistoryItem>)

/** One row of the History list: a single edit or a whole batch. */
sealed interface HistoryItem {
    val key: String
    val createdAt: Long

    data class Single(val record: EditRecord) : HistoryItem {
        override val key: String get() = record.id
        override val createdAt: Long get() = record.createdAt
    }

    /** A batch with its records, oldest first. */
    data class Batch(val batch: EditBatch, val records: List<EditRecord>) : HistoryItem {
        override val key: String get() = "batch:${batch.id}"
        override val createdAt: Long get() = batch.createdAt
        val canRestoreAll: Boolean get() = batch.restoresBatchId == null && records.any { it.canRestore }
    }
}

/** Groups the journal into the History list. Pure, so it is unit-tested without a database. */
object HistoryList {
    fun build(records: List<EditRecord>, batches: List<EditBatch>, zone: ZoneId): List<HistoryDay> {
        val batchIds = batches.mapTo(HashSet()) { it.id }
        val byBatch = records.filter { it.batchId in batchIds }.groupBy { it.batchId }
        val items = buildList {
            records.filter { it.batchId == null || it.batchId !in batchIds }.forEach { add(HistoryItem.Single(it)) }
            batches.forEach { batch ->
                val members = byBatch[batch.id].orEmpty().sortedWith(compareBy({ it.createdAt }, { it.id }))
                val anyResult = batch.succeeded + batch.failed + batch.cancelled > 0
                if (members.isNotEmpty() || anyResult) add(HistoryItem.Batch(batch, members))
            }
        }.sortedWith(compareByDescending<HistoryItem> { it.createdAt }.thenByDescending { it.key })
        return items
            .groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
            .map { (date, dayItems) -> HistoryDay(date, dayItems) }
    }
}
