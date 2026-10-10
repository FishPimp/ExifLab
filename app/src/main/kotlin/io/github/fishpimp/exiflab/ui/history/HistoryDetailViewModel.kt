package io.github.fishpimp.exiflab.ui.history

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.fishpimp.exiflab.appGraph
import io.github.fishpimp.exiflab.data.history.EditRecord
import io.github.fishpimp.exiflab.data.history.FieldDiffCodec
import io.github.fishpimp.exiflab.data.history.SaveError
import io.github.fishpimp.exiflab.data.photos.PhotoAccessException
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.save.SaveOutcome
import io.github.fishpimp.exiflab.data.settings.BackupRetention
import io.github.fishpimp.exiflab.metadata.edit.FieldDiff
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

/** One edit in detail. [record] is null once loaded when the edit no longer exists. */
data class HistoryDetailUiState(
    val isLoading: Boolean = true,
    val record: EditRecord? = null,
    val diff: List<FieldDiff> = emptyList(),
    /** Size of the record's backup, null when there is none on disk. */
    val backupBytes: Long? = null,
    val retention: BackupRetention = BackupRetention.Default,
    /** A restore, delete or open is running; actions are disabled meanwhile. */
    val busy: Boolean = false,
    val zone: ZoneId = ZoneId.systemDefault(),
)

/** One-off results of detail actions. */
sealed interface HistoryDetailMessage {
    data object Restored : HistoryDetailMessage
    data class RestoreFailed(val error: SaveError?) : HistoryDetailMessage
    data object BackupDeleted : HistoryDetailMessage
    data object BackupDeleteFailed : HistoryDetailMessage
    data object PhotoUnavailable : HistoryDetailMessage
    data class OpenPhoto(val ref: PhotoRef) : HistoryDetailMessage
    data class OpenRecord(val recordId: String) : HistoryDetailMessage
}

/** One History record: its diff and backup, with restore, delete-backup and open-photo actions. */
class HistoryDetailViewModel(
    record: Flow<EditRecord?>,
    retention: Flow<BackupRetention>,
    private val backupSize: suspend (String) -> Long?,
    private val restore: suspend (String) -> SaveOutcome,
    private val deleteBackup: suspend (String) -> Boolean,
    private val resolvePhoto: suspend (PhotoRef) -> PhotoRef?,
    zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val messageChannel = Channel<HistoryDetailMessage>(Channel.BUFFERED)

    val messages: Flow<HistoryDetailMessage> = messageChannel.receiveAsFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val loaded = record.mapLatest { current ->
        Triple(
            current,
            current?.diffJson?.let(FieldDiffCodec::decode).orEmpty(),
            current?.backupPath?.let { backupSize(it) },
        )
    }

    val state: StateFlow<HistoryDetailUiState> = combine(loaded, retention.distinctUntilChanged(), busy) { (current, diff, bytes), keep, working ->
        HistoryDetailUiState(
            isLoading = false,
            record = current,
            diff = diff,
            backupBytes = bytes,
            retention = keep,
            busy = working,
            zone = zone,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryDetailUiState(zone = zone))

    fun restoreOriginal() = perform { record ->
        val message = when (val outcome = restore(record.id)) {
            is SaveOutcome.Saved -> HistoryDetailMessage.Restored
            is SaveOutcome.Failed -> HistoryDetailMessage.RestoreFailed(outcome.error)
            is SaveOutcome.RolledBack -> HistoryDetailMessage.RestoreFailed(outcome.error)
            else -> HistoryDetailMessage.RestoreFailed(null)
        }
        messageChannel.send(message)
    }

    fun deleteBackup() = perform { record ->
        messageChannel.send(if (deleteBackup(record.id)) HistoryDetailMessage.BackupDeleted else HistoryDetailMessage.BackupDeleteFailed)
    }

    fun openPhoto() = perform { record ->
        val ref = resolvePhoto(record.photoRef())
        messageChannel.send(if (ref != null) HistoryDetailMessage.OpenPhoto(ref) else HistoryDetailMessage.PhotoUnavailable)
    }

    /** Opens the record that this one restores, or the edit it was restored by. */
    fun openRecord(recordId: String) {
        viewModelScope.launch { messageChannel.send(HistoryDetailMessage.OpenRecord(recordId)) }
    }

    private fun perform(action: suspend (EditRecord) -> Unit) {
        val record = state.value.record ?: return
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                action(record)
            } finally {
                busy.value = false
            }
        }
    }

    companion object {
        fun factory(recordId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val graph = this[APPLICATION_KEY]!!.appGraph
                HistoryDetailViewModel(
                    record = graph.editHistoryDao.observeRecord(recordId),
                    retention = graph.settingsRepository.settings.map { it.backupRetention },
                    backupSize = graph.backupManager::backupSize,
                    restore = { id -> graph.photoSaver.restoreOriginal(id) },
                    deleteBackup = graph.backupManager::deleteBackup,
                    resolvePhoto = { ref ->
                        try {
                            graph.photoRepository.resolve(ref.uri.toUri(), ref.origin).copy(parentDocumentUri = ref.parentDocumentUri)
                        } catch (_: PhotoAccessException) {
                            null
                        }
                    },
                )
            }
        }
    }
}
