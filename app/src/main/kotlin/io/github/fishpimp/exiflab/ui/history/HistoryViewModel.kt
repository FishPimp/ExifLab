package io.github.fishpimp.exiflab.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.fishpimp.exiflab.appGraph
import io.github.fishpimp.exiflab.data.history.EditBatch
import io.github.fishpimp.exiflab.data.history.EditRecord
import io.github.fishpimp.exiflab.data.save.BatchRestoreResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** One-off results the History screen shows as a snackbar. */
sealed interface HistoryMessage {
    data class BatchRestored(val restored: Int, val failed: Int) : HistoryMessage
}

/** The History list: the journal grouped by day and batch, plus batch restores. */
class HistoryViewModel(
    records: Flow<List<EditRecord>>,
    batches: Flow<List<EditBatch>>,
    private val restoreBatch: suspend (String) -> BatchRestoreResult,
    zone: () -> ZoneId = ZoneId::systemDefault,
    clock: () -> Long = System::currentTimeMillis,
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val restoringBatch = MutableStateFlow<String?>(null)
    private val messageChannel = Channel<HistoryMessage>(Channel.BUFFERED)

    val messages: Flow<HistoryMessage> = messageChannel.receiveAsFlow()

    val state: StateFlow<HistoryUiState> = combine(
        combine(records, batches) { r, b ->
            val currentZone = zone()
            Triple(HistoryList.build(r, b, currentZone), currentZone, Instant.ofEpochMilli(clock()).atZone(currentZone).toLocalDate())
        }.flowOn(computeDispatcher),
        restoringBatch,
    ) { (days, currentZone, today), restoring ->
        HistoryUiState(isLoading = false, days = days, today = today, zone = currentZone, restoringBatchId = restoring)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    /** Restores every original of [batchId]; the result arrives as a [HistoryMessage]. */
    fun restoreBatch(batchId: String) {
        if (restoringBatch.value != null) return
        restoringBatch.value = batchId
        viewModelScope.launch {
            try {
                val result = restoreBatch.invoke(batchId)
                messageChannel.send(HistoryMessage.BatchRestored(result.restored, result.failed))
            } finally {
                restoringBatch.value = null
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val graph = this[APPLICATION_KEY]!!.appGraph
                val dao = graph.editHistoryDao
                HistoryViewModel(
                    records = dao.observeRecords(),
                    batches = dao.observeBatches(),
                    restoreBatch = graph.photoSaver::restoreBatch,
                )
            }
        }
    }
}
