package io.github.fishpimp.exiflab.ui.batch

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.fishpimp.exiflab.appGraph
import io.github.fishpimp.exiflab.data.batch.BatchItemStatus
import io.github.fishpimp.exiflab.data.batch.BatchJob
import io.github.fishpimp.exiflab.data.batch.BatchJobItem
import io.github.fishpimp.exiflab.data.batch.BatchJobRepository
import io.github.fishpimp.exiflab.data.batch.BatchStatusCount
import io.github.fishpimp.exiflab.data.save.BatchRestoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A running or finished batch: progress from the job's counts, the per-photo list once it ended. */
@Immutable
data class BatchRunUiState(
    val isLoading: Boolean = true,
    val job: BatchJob? = null,
    val counts: Map<BatchItemStatus, Int> = emptyMap(),
    val runningNames: List<String> = emptyList(),
    /** Every photo with its outcome; only loaded once the job has ended. */
    val items: List<BatchJobItem> = emptyList(),
    /** Summary filter; null shows every photo. */
    val filter: BatchItemStatus? = null,
    val restoring: Boolean = false,
    val restored: Boolean = false,
) {
    val total: Int get() = job?.total ?: 0
    val done: Int get() = counts.filterKeys { it.isFinal }.values.sum()
    fun count(status: BatchItemStatus): Int = counts[status] ?: 0

    /** Photos that were written (in place, sidecar or copy). */
    val written: Int get() = count(BatchItemStatus.Saved) + count(BatchItemStatus.Sidecar) + count(BatchItemStatus.Copy)

    /** In-place edits and sidecars can be put back; copies leave the originals untouched. */
    val canRestore: Boolean get() = job?.editBatchId != null && !restored && (count(BatchItemStatus.Saved) + count(BatchItemStatus.Sidecar)) > 0

    val visibleItems: List<BatchJobItem> get() = filter?.let { status -> items.filter { it.status == status } } ?: items
}

sealed interface BatchRunMessage {
    data class Restored(val result: BatchRestoreResult) : BatchRunMessage
}

/** Follows batch job [jobId] in Room, so it reattaches to a job that runs while the app was closed. */
@OptIn(ExperimentalCoroutinesApi::class)
class BatchRunViewModel(
    private val jobId: String,
    private val jobs: BatchJobRepository,
    private val restoreBatch: suspend (String) -> BatchRestoreResult,
) : ViewModel() {
    private val local = MutableStateFlow(Local())
    private val messageChannel = Channel<BatchRunMessage>(Channel.BUFFERED)

    val messages: Flow<BatchRunMessage> = messageChannel.receiveAsFlow()

    private val job = jobs.observeJob(jobId)

    // The per-photo list changes with every photo; it is only read once the job has ended.
    private val items: Flow<List<BatchJobItem>> = job.map { it?.state?.isDone == true }
        .distinctUntilChanged()
        .flatMapLatest { done -> if (done) jobs.observeItems(jobId) else flowOf(emptyList()) }

    val state: StateFlow<BatchRunUiState> = combine(
        job,
        jobs.observeCounts(jobId),
        jobs.observeRunningNames(jobId),
        items,
        local,
    ) { job, counts, running, items, local ->
        BatchRunUiState(
            isLoading = false,
            job = job,
            counts = counts.toMap(),
            runningNames = running.filterNotNull(),
            items = items,
            filter = local.filter,
            restoring = local.restoring,
            restored = local.restored,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BatchRunUiState())

    fun setFilter(status: BatchItemStatus?) = local.update { it.copy(filter = status) }

    /** Stops after the photos in progress. */
    fun cancel() {
        viewModelScope.launch { jobs.cancel(jobId) }
    }

    /** Puts back every original this batch changed (a new batch in History, which can be undone). */
    fun restoreAll() {
        val batchId = state.value.job?.editBatchId ?: return
        if (local.value.restoring) return
        local.update { it.copy(restoring = true) }
        viewModelScope.launch {
            try {
                val result = restoreBatch(batchId)
                local.update { it.copy(restored = result.restored > 0) }
                messageChannel.send(BatchRunMessage.Restored(result))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageChannel.send(BatchRunMessage.Restored(BatchRestoreResult(null, 0, 0)))
            } finally {
                local.update { it.copy(restoring = false) }
            }
        }
    }

    private data class Local(val filter: BatchItemStatus? = null, val restoring: Boolean = false, val restored: Boolean = false)

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(jobId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val graph = this[APPLICATION_KEY]!!.appGraph
                BatchRunViewModel(jobId, graph.batchJobs, restoreBatch = { graph.photoSaver.restoreBatch(it) })
            }
        }
    }
}

private fun List<BatchStatusCount>.toMap(): Map<BatchItemStatus, Int> = associate { it.status to it.count }
