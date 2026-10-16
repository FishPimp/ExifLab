package io.github.fishpimp.exiflab.ui.selection

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.batch.BatchJobRepository
import io.github.fishpimp.exiflab.data.batch.BatchKind
import io.github.fishpimp.exiflab.data.batch.BatchOperation
import io.github.fishpimp.exiflab.data.batch.BatchPhotos
import io.github.fishpimp.exiflab.data.history.EditHistoryDatabase
import io.github.fishpimp.exiflab.ui.navigation.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

/** Choosing a batch action keeps the selection until the batch really starts. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SelectionBatchStartTest {
    private lateinit var database: EditHistoryDatabase
    private lateinit var jobs: BatchJobRepository

    private val photos = listOf(BatchPhotos.folder("a.jpg"), BatchPhotos.folder("b.jpg"), BatchPhotos.folder("c.jpg"))

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EditHistoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        jobs = BatchJobRepository(dao = { database.batchJobs() }, scheduler = {})
    }

    @After fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `backing out of the batch editor keeps the selection, starting the batch clears it`() = runBlocking<Unit> {
        val vm = SelectionViewModel(photos, jobs)
        vm.selectionActions.onBegin(photos[0].uri)
        vm.selectionActions.onToggle(photos[2].uri)

        vm.startBatch(BatchKind.Location)
        val first: Route.BatchEdit = withTimeout(10_000) { vm.batchEditRequests.first() }
        // The editor is open; the user backs out and picks another action instead.
        delay(100)
        assertThat(vm.selection.value.active).isTrue()
        assertThat(vm.selection.value.selected).containsExactly(photos[0].uri, photos[2].uri)

        vm.startBatch(BatchKind.Author)
        val second = withTimeout(10_000) { vm.batchEditRequests.first() }
        assertThat(second.kind).isEqualTo(BatchKind.Author)
        assertThat(jobs.items(second.jobId).map { it.photoUri }).containsExactly(photos[0].uri, photos[2].uri).inOrder()

        // The abandoned draft never starts the clearing; the started one does.
        assertThat(jobs.start(first.jobId, BatchOperation.SetLocation(59.3, 18.0, null), "", null)).isTrue()
        delay(200)
        assertThat(vm.selection.value.active).isTrue()

        jobs.start(second.jobId, BatchOperation.SetAuthorship("Maja", null, changeCopyright = false), "", null)
        withTimeout(10_000) { vm.selection.first { !it.active } }
        assertThat(vm.selection.value.selected).isEmpty()
    }
}
