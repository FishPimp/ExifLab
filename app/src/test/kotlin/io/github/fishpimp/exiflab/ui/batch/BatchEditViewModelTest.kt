package io.github.fishpimp.exiflab.ui.batch

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.batch.BatchFakePlanner
import io.github.fishpimp.exiflab.data.batch.BatchJobRepository
import io.github.fishpimp.exiflab.data.batch.BatchJobState
import io.github.fishpimp.exiflab.data.batch.BatchKind
import io.github.fishpimp.exiflab.data.batch.BatchOperation
import io.github.fishpimp.exiflab.data.batch.BatchPhotos
import io.github.fishpimp.exiflab.data.batch.storedTitle
import io.github.fishpimp.exiflab.data.history.EditHistoryDatabase
import io.github.fishpimp.exiflab.data.places.PlaceSearchState
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.edit.StripCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The batch editor from draft to started job, with a real job table. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BatchEditViewModelTest {
    private lateinit var database: EditHistoryDatabase
    private lateinit var jobs: BatchJobRepository
    private val enqueued = mutableListOf<String>()
    private val scope = CoroutineScope(Dispatchers.Default)

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EditHistoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        jobs = BatchJobRepository(dao = { database.batchJobs() }, scheduler = { enqueued += it })
    }

    @After fun tearDown() {
        scope.cancel()
        database.close()
        Dispatchers.resetMain()
    }

    private val photos = listOf(BatchPhotos.folder("a.jpg"), BatchPhotos.picker("p.jpg"), BatchPhotos.folder("same.jpg"))

    private fun viewModel(jobId: String, kind: BatchKind) = BatchEditViewModel(
        jobId = jobId,
        kind = kind,
        jobs = jobs,
        analyzer = BatchAnalyzer(
            readReport = { BatchPhotos.report(it) },
            planner = { BatchFakePlanner() },
            canWrite = { it == ImageFormat.Jpeg },
        ),
        readReport = { BatchPhotos.report(it) },
        readOtherPhoto = { error("not used") },
        resolveFolder = { uri -> CopyFolder("$uri/document", "Copies") },
        searchPlaces = { flowOf(PlaceSearchState.Idle) },
    ).also { vm -> scope.launch { vm.state.collect { } } }

    private suspend fun BatchEditViewModel.awaitState(predicate: (BatchEditUiState) -> Boolean) =
        withTimeout(10_000) { state.first(predicate) }

    @Test
    fun `author fields left empty keep each photo's value, and starting queues the job with the folder`() = runBlocking<Unit> {
        val id = jobs.createDraft(photos)
        val vm = viewModel(id, BatchKind.Author)
        val loaded = vm.awaitState { it.photos is DraftPhotos.Loaded && it.sample != null }
        assertThat(loaded.count).isEqualTo(3)
        assertThat(vm.operation()).isNull()

        vm.onArtistChange("  Maja Lindqvist ")
        assertThat(vm.operation()).isEqualTo(BatchOperation.SetAuthorship("Maja Lindqvist", null, changeArtist = true, changeCopyright = false))

        vm.openPreview()
        val preview = vm.awaitState { it.page == BatchEditorPage.Preview && it.preview?.isComplete == true }.preview!!
        assertThat(preview.unchanged).isEqualTo(1)
        assertThat(preview.copiesNeedingFolder).isEqualTo(1)
        assertThat(preview.written(hasFolder = false)).isEqualTo(1)

        vm.onCopyFolderPicked("content://tree/out")
        vm.awaitState { it.copyFolder != null }
        val events = java.util.concurrent.CopyOnWriteArrayList<BatchEditEvent>()
        scope.launch { vm.events.collect { events += it } }
        vm.start()
        withTimeout(10_000) { while (events.isEmpty()) kotlinx.coroutines.delay(10) }

        assertThat(events).containsExactly(BatchEditEvent.Started(id))
        val job = jobs.job(id)!!
        assertThat(job.state).isEqualTo(BatchJobState.Queued)
        assertThat(job.title).isEqualTo(BatchKind.Author.storedTitle)
        assertThat(job.copyFolderUri).isEqualTo("content://tree/out/document")
        assertThat(job.operation).isEqualTo(vm.operation())
        assertThat(enqueued).containsExactly(id)

        // Back from the preview returns to the fields; the editor itself closes from there.
        assertThat(vm.back()).isTrue()
        assertThat(vm.back()).isFalse()
    }

    @Test
    fun `strip categories toggle and a started job cannot be edited again`() = runBlocking<Unit> {
        val id = jobs.createDraft(photos)
        val vm = viewModel(id, BatchKind.Strip)
        vm.awaitState { it.photos is DraftPhotos.Loaded }
        vm.toggleStrip(StripCategory.Location)
        vm.toggleStrip(StripCategory.Dates)
        vm.toggleStrip(StripCategory.Dates)
        assertThat(vm.operation()).isEqualTo(BatchOperation.Strip.of(setOf(StripCategory.Location)))

        jobs.start(id, vm.operation()!!, "Strip", null)
        val again = viewModel(id, BatchKind.Strip)
        assertThat(again.awaitState { it.photos !is DraftPhotos.Loading }.photos).isEqualTo(DraftPhotos.Missing)
    }
}
