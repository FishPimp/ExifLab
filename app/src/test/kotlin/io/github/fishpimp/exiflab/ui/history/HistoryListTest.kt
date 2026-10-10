package io.github.fishpimp.exiflab.ui.history

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.history.EditBatch
import io.github.fishpimp.exiflab.data.history.EditState
import io.github.fishpimp.exiflab.data.history.SaveError
import io.github.fishpimp.exiflab.data.save.SaveOutcome
import io.github.fishpimp.exiflab.data.settings.BackupRetention
import io.github.fishpimp.exiflab.screenshots.FakeHistory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryListTest {
    private val zone = FakeHistory.zone

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `groups by day newest first and folds batch members into one item`() {
        val days = HistoryList.build(FakeHistory.records, listOf(FakeHistory.batch), zone)

        assertThat(days.map { it.date }).containsExactly(
            FakeHistory.today,
            FakeHistory.today.minusDays(1),
            FakeHistory.today.minusDays(3),
            FakeHistory.today.minusDays(4),
        ).inOrder()
        assertThat(days.first().items.map { it.key }).containsExactly("r1", "r2", "r3", "r4").inOrder()
        val yesterday = days[1].items
        assertThat(yesterday.map { it.key }).containsExactly("r5", "batch:b1", "r6").inOrder()
        val batch = yesterday[1] as HistoryItem.Batch
        assertThat(batch.records.map { it.id }).containsExactly("b1-1", "b1-2", "b1-3", "b1-4").inOrder()
        assertThat(batch.canRestoreAll).isTrue()
        // Batch members never show up on their own.
        assertThat(days.flatMap { it.items }.filterIsInstance<HistoryItem.Single>().map { it.record.batchId }.toSet())
            .containsExactly(null)
    }

    @Test
    fun `restore batches cannot be restored as a whole, and empty batches are hidden`() {
        val restoreBatch = EditBatch("rb", "Lofoten", createdAt = 5, total = 1, succeeded = 1, restoresBatchId = "b1")
        val empty = EditBatch("empty", "Nothing", createdAt = 6, total = 2)
        val member = FakeHistory.record("m", "a.jpg", createdAt = 5, batchId = "rb")

        val items = HistoryList.build(listOf(member), listOf(restoreBatch, empty), zone).flatMap { it.items }

        assertThat(items.map { it.key }).containsExactly("batch:rb")
        assertThat((items.single() as HistoryItem.Batch).canRestoreAll).isFalse()
    }

    @Test
    fun `records of an unknown batch are listed on their own`() {
        val orphan = FakeHistory.record("o", "a.jpg", createdAt = 5, batchId = "gone")
        val items = HistoryList.build(listOf(orphan), emptyList(), zone).single().items
        assertThat(items.single()).isInstanceOf(HistoryItem.Single::class.java)
    }

    @Test
    fun `view model groups the journal on a fixed clock and reports batch restores`() = runTest {
        val restored = mutableListOf<String>()
        val viewModel = HistoryViewModel(
            records = flowOf(FakeHistory.records),
            batches = flowOf(listOf(FakeHistory.batch)),
            restoreBatch = { id ->
                restored += id
                io.github.fishpimp.exiflab.data.save.BatchRestoreResult("new", restored = 3, failed = 0)
            },
            zone = { zone },
            clock = { FakeHistory.inPlace.createdAt },
            computeDispatcher = Dispatchers.Main,
        )
        val collector = launch { viewModel.state.collect { } }
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.isLoading).isFalse()
        assertThat(state.today).isEqualTo(LocalDate.of(2026, 10, 10))
        assertThat(state.days).hasSize(4)

        val message = launch { assertThat(viewModel.messages.first()).isEqualTo(HistoryMessage.BatchRestored(3, 0)) }
        viewModel.restoreBatch("b1")
        advanceUntilIdle()
        message.join()
        assertThat(restored).containsExactly("b1")
        assertThat(viewModel.state.value.restoringBatchId).isNull()
        collector.cancel()
    }

    @Test
    fun `detail view model decodes the diff and turns outcomes into messages`() = runTest {
        val record = MutableStateFlow(FakeHistory.inPlace)
        var outcome: SaveOutcome = SaveOutcome.Saved("x", FakeHistory.inPlace.mode, "uri")
        val viewModel = HistoryDetailViewModel(
            record = record,
            retention = flowOf(BackupRetention.Days90),
            backupSize = { 1234L },
            restore = { outcome },
            deleteBackup = { true },
            resolvePhoto = { null },
            zone = zone,
        )
        val collector = launch { viewModel.state.collect { } }
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.diff).isEqualTo(FakeHistory.authorDiff)
        assertThat(state.backupBytes).isEqualTo(1234L)
        assertThat(state.retention).isEqualTo(BackupRetention.Days90)

        val messages = mutableListOf<HistoryDetailMessage>()
        val gather = launch { viewModel.messages.collect { messages += it } }
        viewModel.restoreOriginal()
        advanceUntilIdle()
        outcome = SaveOutcome.RolledBack("y", SaveError.VerificationFailed)
        viewModel.restoreOriginal()
        advanceUntilIdle()
        viewModel.openPhoto()
        advanceUntilIdle()
        viewModel.deleteBackup()
        advanceUntilIdle()

        assertThat(messages).containsExactly(
            HistoryDetailMessage.Restored,
            HistoryDetailMessage.RestoreFailed(SaveError.VerificationFailed),
            HistoryDetailMessage.PhotoUnavailable,
            HistoryDetailMessage.BackupDeleted,
        ).inOrder()

        record.value = FakeHistory.inPlace.copy(state = EditState.Restored)
        advanceUntilIdle()
        assertThat(viewModel.state.value.record!!.state).isEqualTo(EditState.Restored)
        gather.cancel()
        collector.cancel()
    }
}
