package io.github.fishpimp.exiflab.data.batch

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.history.EditHistoryDatabase
import io.github.fishpimp.exiflab.data.history.EditMode
import io.github.fishpimp.exiflab.data.history.EditState
import io.github.fishpimp.exiflab.data.history.SaveError
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.save.SaveTarget
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.edit.StripCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The batch worker's logic with a real (in-memory) job table and a fake save pipeline. */
@RunWith(RobolectricTestRunner::class)
class BatchRunnerTest {
    private lateinit var database: EditHistoryDatabase
    private lateinit var dao: BatchJobDao
    private var nextId = 0

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EditHistoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.batchJobs()
    }

    @After fun tearDown() = database.close()

    private val author = BatchOperation.SetAuthorship("Maja", null, changeCopyright = false)

    private fun repository() = BatchJobRepository(dao = { dao }, scheduler = {}, newId = { "job-${nextId++}" })

    private suspend fun startedJob(refs: List<PhotoRef>, operation: BatchOperation = author, copyFolder: String? = null): String {
        val repository = repository()
        val id = repository.createDraft(refs)
        assertThat(repository.start(id, operation, "Batch", copyFolder)).isTrue()
        return id
    }

    private fun runner(
        saving: FakeBatchSaving,
        destinations: FakeDestinations = FakeDestinations(),
        unreadable: Set<String> = emptySet(),
    ) = BatchRunner(
        jobs = dao,
        saving = saving,
        newDestinations = { destinations },
        readReport = { ref ->
            if (ref.displayName in unreadable) error("cannot read")
            BatchPhotos.report(ref)
        },
        planner = { BatchFakePlanner() },
    )

    private suspend fun statuses(jobId: String) = dao.items(jobId).associate { it.displayName to it.status }

    @Test
    fun `each kind of photo is routed like the single editor would save it`() = runBlocking<Unit> {
        val refs = listOf(
            BatchPhotos.folder("a.jpg"),
            BatchPhotos.folder("b.RAF", writable = false),
            BatchPhotos.folder("c.heic"),
            BatchPhotos.picker("IMG_0001.jpg"),
            BatchPhotos.picker("e.RAF"),
            BatchPhotos.folder("same.jpg"),
            BatchPhotos.folder("broken.jpg"),
            BatchPhotos.folder("fail.jpg"),
        )
        val id = startedJob(refs, copyFolder = "content://fake/tree/out/document/out")
        val saving = FakeBatchSaving()
        val destinations = FakeDestinations()

        val ended = runner(saving, destinations, unreadable = setOf("broken.jpg")).run(id)!!

        assertThat(ended.state).isEqualTo(BatchJobState.Finished)
        assertThat(ended.editBatchId).isEqualTo(FakeBatchSaving.BATCH_ID)
        assertThat(statuses(id)).containsExactly(
            "a.jpg", BatchItemStatus.Saved,
            "b.RAF", BatchItemStatus.Sidecar,
            // HEIF goes to a sidecar until its writer reports that it can write.
            "c.heic", BatchItemStatus.Sidecar,
            "IMG_0001.jpg", BatchItemStatus.Copy,
            "e.RAF", BatchItemStatus.Sidecar,
            "same.jpg", BatchItemStatus.Skipped,
            "broken.jpg", BatchItemStatus.Failed,
            "fail.jpg", BatchItemStatus.Failed,
        )
        val items = dao.items(id).associateBy { it.displayName }
        assertThat(items.getValue("same.jpg").reason).isEqualTo(BatchItemReason.NothingToChange)
        assertThat(items.getValue("broken.jpg").reason).isEqualTo(BatchItemReason.Unreadable)
        assertThat(items.getValue("fail.jpg").saveError).isEqualTo(SaveError.WriteFailed)
        assertThat(items.getValue("a.jpg").recordId).isEqualTo("record-a.jpg")
        assertThat(items.values.filter { it.status.isFinal }.all { it.finishedAt != null }).isTrue()
        // The copy keeps the original name, renamed because the folder already has one.
        assertThat(items.getValue("IMG_0001.jpg").targetUri).isEqualTo("content://fake/tree/out/document/out/IMG_0001 (1).jpg")
        // A picker RAW has no folder of its own; its sidecar goes to the chosen folder.
        assertThat(destinations.created).contains("content://fake/tree/out/document/out/e.RAF.xmp")
        // The unreadable photo is counted by the runner; the journaled failure by the pipeline.
        assertThat(saving.failedCount).isEqualTo(2)
    }

    @Test
    fun `photos that already record another offset are skipped with their own reason`() = runBlocking<Unit> {
        val id = startedJob(listOf(BatchPhotos.folder("zone.jpg"), BatchPhotos.folder("a.jpg")))
        runner(FakeBatchSaving()).run(id)
        val items = dao.items(id).associateBy { it.displayName }
        assertThat(items.getValue("zone.jpg").status).isEqualTo(BatchItemStatus.Skipped)
        assertThat(items.getValue("zone.jpg").reason).isEqualTo(BatchItemReason.AlreadyHasOtherZone)
        assertThat(items.getValue("a.jpg").status).isEqualTo(BatchItemStatus.Saved)
    }

    @Test
    fun `without a folder read-only photos are skipped and HEIF is written in place once supported`() = runBlocking<Unit> {
        val id = startedJob(listOf(BatchPhotos.picker("p.jpg"), BatchPhotos.picker("q.RAF"), BatchPhotos.folder("c.heic")))
        val saving = FakeBatchSaving(writable = setOf(ImageFormat.Jpeg, ImageFormat.Heif))

        runner(saving).run(id)

        val items = dao.items(id).associateBy { it.displayName }
        assertThat(items.getValue("p.jpg").reason).isEqualTo(BatchItemReason.ReadOnly)
        assertThat(items.getValue("q.RAF").reason).isEqualTo(BatchItemReason.NoSidecarPlace)
        assertThat(items.getValue("c.heic").status).isEqualTo(BatchItemStatus.Saved)
        assertThat(saving.saves.map { it.second }).containsExactly(SaveTarget.InPlace)
    }

    @Test
    fun `stripping skips files that only get sidecars`() = runBlocking<Unit> {
        val id = startedJob(
            listOf(BatchPhotos.folder("a.jpg"), BatchPhotos.folder("b.DNG")),
            operation = BatchOperation.Strip.of(setOf(StripCategory.Location)),
        )
        runner(FakeBatchSaving()).run(id)

        val items = dao.items(id).associateBy { it.displayName }
        assertThat(items.getValue("a.jpg").status).isEqualTo(BatchItemStatus.Saved)
        assertThat(items.getValue("b.DNG").reason).isEqualTo(BatchItemReason.CannotStrip)
    }

    @Test
    fun `no more than two photos are saved at once`() = runBlocking<Unit> {
        val id = startedJob((1..12).map { BatchPhotos.folder("p$it.jpg") })
        val saving = FakeBatchSaving(saveDelayMs = 25)

        runner(saving).run(id)

        assertThat(saving.maxActive.get()).isEqualTo(BatchRunner.DEFAULT_CONCURRENCY)
        assertThat(saving.saves).hasSize(12)
        assertThat(statuses(id).values.toSet()).containsExactly(BatchItemStatus.Saved)
    }

    @Test
    fun `every item is stored as running while it is saved`() = runBlocking<Unit> {
        lateinit var id: String
        val seen = mutableListOf<BatchItemStatus?>()
        val saving = FakeBatchSaving(onSave = { ref -> seen += dao.items(id).first { it.photoUri == ref.uri }.status })
        id = startedJob(listOf(BatchPhotos.folder("a.jpg"), BatchPhotos.folder("b.jpg")))
        val progress = mutableListOf<BatchProgress>()

        runner(saving).run(id) { progress += it }

        assertThat(seen).containsExactly(BatchItemStatus.Running, BatchItemStatus.Running)
        assertThat(progress.first().done).isEqualTo(0)
        assertThat(progress.last()).isEqualTo(BatchProgress(total = 2, done = 2, failed = 0, skipped = 0))
    }

    @Test
    fun `cancel lets the photos in progress finish and skips the rest`() = runBlocking<Unit> {
        lateinit var id: String
        val saving = FakeBatchSaving(saveDelayMs = 20, onSave = { dao.requestCancel(id) })
        id = startedJob((1..10).map { BatchPhotos.folder("p$it.jpg") })

        val ended = runner(saving).run(id)!!

        assertThat(ended.state).isEqualTo(BatchJobState.Cancelled)
        val items = dao.items(id)
        val saved = items.count { it.status == BatchItemStatus.Saved }
        assertThat(saved).isIn(1..BatchRunner.DEFAULT_CONCURRENCY)
        assertThat(saving.saves).hasSize(saved)
        assertThat(items.none { it.status == BatchItemStatus.Running || it.status == BatchItemStatus.Pending }).isTrue()
        assertThat(items.filter { it.status == BatchItemStatus.Skipped }.map { it.reason }.toSet()).containsExactly(BatchItemReason.Cancelled)
        assertThat(saving.cancelledCount).isEqualTo(10 - saved)
    }

    @Test
    fun `a job resumes its remaining items and settles interrupted ones from the journal`() = runBlocking<Unit> {
        val refs = listOf("done.jpg", "committed.jpg", "rolledback.jpg", "unstarted.jpg", "pending.jpg").map { BatchPhotos.folder(it) }
        val id = startedJob(refs, copyFolder = null)
        dao.markRunning(id, FakeBatchSaving.BATCH_ID, now = 1)
        val items = dao.items(id).associateBy { it.displayName }
        dao.updateItem(items.getValue("done.jpg").copy(status = BatchItemStatus.Saved, recordId = "earlier", finishedAt = 1))
        dao.updateItem(items.getValue("committed.jpg").copy(status = BatchItemStatus.Running))
        dao.updateItem(items.getValue("rolledback.jpg").copy(status = BatchItemStatus.Running))
        dao.updateItem(items.getValue("unstarted.jpg").copy(status = BatchItemStatus.Running, targetUri = "content://out/empty-copy"))
        val saving = FakeBatchSaving()
        saving.records[refs[1].uri] = FakeBatchSaving.record(refs[1], EditMode.InPlace, EditState.Committed)
        saving.records[refs[2].uri] = FakeBatchSaving.record(refs[2], EditMode.InPlace, EditState.RolledBack, error = SaveError.Interrupted)
        saving.failedCount = 1
        val destinations = FakeDestinations()

        val ended = runner(saving, destinations).run(id)!!

        assertThat(ended.state).isEqualTo(BatchJobState.Finished)
        assertThat(saving.batchCreated).isEqualTo(0)
        assertThat(saving.recoveries).isEqualTo(1)
        // Finished and committed photos are not written again.
        assertThat(saving.saves.map { it.first.displayName }).containsExactly("rolledback.jpg", "unstarted.jpg", "pending.jpg")
        assertThat(statuses(id).values.toSet()).containsExactly(BatchItemStatus.Saved)
        assertThat(dao.items(id).first { it.displayName == "committed.jpg" }.recordId).isEqualTo("record-committed.jpg")
        assertThat(dao.items(id).first { it.displayName == "done.jpg" }.recordId).isEqualTo("earlier")
        assertThat(destinations.deleted).containsExactly("content://out/empty-copy")
        // The rolled-back attempt's failure no longer counts.
        assertThat(saving.failedCount).isEqualTo(0)
    }

    @Test
    fun `a worker stopped by the system leaves the job resumable`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val saving = FakeBatchSaving(onSave = { ref ->
            if (ref.displayName == "b.jpg") {
                gate.complete(Unit)
                awaitCancellation()
            }
        })
        val id = startedJob(listOf(BatchPhotos.folder("a.jpg"), BatchPhotos.folder("b.jpg"), BatchPhotos.folder("c.jpg")))
        val running = async { runner(saving).run(id) }
        gate.await()
        running.cancelAndJoin()

        assertThat(dao.job(id)!!.state).isEqualTo(BatchJobState.Running)
        assertThat(dao.items(id).first { it.displayName == "b.jpg" }.status).isEqualTo(BatchItemStatus.Running)

        val resumed = runner(FakeBatchSaving()).run(id)!!
        assertThat(resumed.state).isEqualTo(BatchJobState.Finished)
        assertThat(statuses(id).values.toSet()).containsExactly(BatchItemStatus.Saved)
    }

    @Test
    fun `drafts and finished jobs are not run`() = runBlocking<Unit> {
        val draft = repository().createDraft(listOf(BatchPhotos.folder("a.jpg")))
        val saving = FakeBatchSaving()
        assertThat(runner(saving).run(draft)!!.state).isEqualTo(BatchJobState.Draft)
        assertThat(runner(saving).run("missing")).isNull()
        assertThat(saving.saves).isEmpty()
    }

    @Test
    fun `drafts keep the selection order without duplicates and start only once`() = runBlocking<Unit> {
        val enqueued = mutableListOf<String>()
        val repository = BatchJobRepository(dao = { dao }, scheduler = { enqueued += it }, newId = { "job" })
        val a = BatchPhotos.folder("a.jpg")
        val b = BatchPhotos.picker("b.jpg")
        val id = repository.createDraft(listOf(a, b, a))

        assertThat(dao.items(id).map { it.photoRef() }).containsExactly(a, b).inOrder()
        assertThat(dao.job(id)!!.total).isEqualTo(2)
        assertThat(repository.start(id, author, "Set author", null)).isTrue()
        assertThat(repository.start(id, author, "Set author", null)).isFalse()
        assertThat(enqueued).containsExactly("job")
        assertThat(dao.job(id)!!.operation).isEqualTo(author)

        repository.resumeUnfinished()
        assertThat(enqueued).containsExactly("job", "job")
    }
}
