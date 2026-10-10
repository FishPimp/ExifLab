package io.github.fishpimp.exiflab.data.save

import androidx.core.net.toUri
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.backup.Sha256
import io.github.fishpimp.exiflab.data.history.EditHistoryDao
import io.github.fishpimp.exiflab.data.history.EditMode
import io.github.fishpimp.exiflab.data.history.EditState
import io.github.fishpimp.exiflab.data.history.FieldDiffCodec
import io.github.fishpimp.exiflab.data.history.SaveError
import io.github.fishpimp.exiflab.data.save.SaveTestHarness.Companion.ORIGINAL
import io.github.fishpimp.exiflab.data.save.SaveTestHarness.Companion.changes
import io.github.fishpimp.exiflab.data.save.SaveTestHarness.Companion.diff
import io.github.fishpimp.exiflab.metadata.write.UnsupportedEditException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The safe save pipeline on real files: success, failures, rollback, crash recovery and restore. */
@RunWith(RobolectricTestRunner::class)
class PhotoSaverTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var h: SaveTestHarness
    private val edited = ORIGINAL + "|edited".toByteArray()

    @Before fun setUp() {
        h = SaveTestHarness(folder.root)
    }

    @After fun tearDown() = h.close()

    @Test
    fun `in place save writes, verifies, backs up the original and commits`() = runBlocking<Unit> {
        val ref = h.photo("IMG_0001.jpg", ORIGINAL)

        val outcome = h.saver().save(ref, changes, diff, SaveTarget.InPlace)

        assertThat(outcome).isInstanceOf(SaveOutcome.Saved::class.java)
        outcome as SaveOutcome.Saved
        assertThat(outcome.mode).isEqualTo(EditMode.InPlace)
        assertThat(outcome.skipped).containsExactly("IPTC")
        assertThat(h.fileOf(ref.uri).readBytes()).isEqualTo(edited)

        val record = h.dao.record(outcome.recordId)!!
        assertThat(record.state).isEqualTo(EditState.Committed)
        assertThat(record.originalSize).isEqualTo(ORIGINAL.size.toLong())
        assertThat(record.originalSha256).isEqualTo(Sha256.of(ORIGINAL).sha256)
        assertThat(record.newSha256).isEqualTo(Sha256.of(edited).sha256)
        assertThat(record.summary).isEqualTo("Artist, Date Taken")
        assertThat(FieldDiffCodec.decode(record.diffJson)).isEqualTo(diff)
        assertThat(record.completedAt).isNotNull()
        assertThat(h.store.file(record.backupPath!!).readBytes()).isEqualTo(ORIGINAL)
        assertThat(record.canRestore).isTrue()
        assertThat(h.tempDir.listFiles().orEmpty()).isEmpty()
        assertThat(h.committedSaves.get()).isEqualTo(1)
    }

    @Test
    fun `in place is rejected for read-only photos, RAW files and formats the writer cannot handle`() = runBlocking<Unit> {
        val saver = h.saver()
        assertThat(saver.save(h.photo("a.jpg", ORIGINAL, writable = false), changes, diff, SaveTarget.InPlace))
            .isEqualTo(SaveOutcome.Rejected(SaveRejection.ReadOnly))
        assertThat(saver.save(h.photo("b.RAF", ORIGINAL), changes, diff, SaveTarget.InPlace))
            .isEqualTo(SaveOutcome.Rejected(SaveRejection.UseSidecar))
        assertThat(saver.save(h.photo("c.webp", ORIGINAL), changes, diff, SaveTarget.InPlace))
            .isEqualTo(SaveOutcome.Rejected(SaveRejection.UnsupportedFormat))
        assertThat(h.dao.observeRecordsOnce()).isEmpty()
    }

    @Test
    fun `a writer that is not available yet makes in-place saves unavailable instead of crashing`() = runBlocking<Unit> {
        val engine = WriteEngine(writer = { TODO("not merged") }, sidecar = { TODO() }, check = { _, _ -> })
        assertThat(engine.canWrite(io.github.fishpimp.exiflab.metadata.ImageFormat.Jpeg)).isFalse()
    }

    @Test
    fun `verification mismatch rolls back to the exact original`() = runBlocking<Unit> {
        val ref = h.photo("IMG_0002.jpg", ORIGINAL)
        h.documents.corruptNext = 1

        val outcome = h.saver().save(ref, changes, diff, SaveTarget.InPlace)

        assertThat(outcome).isInstanceOf(SaveOutcome.RolledBack::class.java)
        assertThat((outcome as SaveOutcome.RolledBack).error).isEqualTo(SaveError.VerificationFailed)
        assertThat(h.fileOf(ref.uri).readBytes()).isEqualTo(ORIGINAL)
        val record = h.dao.record(outcome.recordId)!!
        assertThat(record.state).isEqualTo(EditState.RolledBack)
        assertThat(record.saveError).isEqualTo(SaveError.VerificationFailed)
        // The original is back in place, so its backup is no longer needed.
        assertThat(record.backupPath).isNull()
        assertThat(h.store.files()).isEmpty()
        assertThat(h.tempDir.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `a write that fails half way rolls back`() = runBlocking<Unit> {
        val ref = h.photo("IMG_0003.jpg", ORIGINAL)
        h.documents.failAfterWriting = 1

        val outcome = h.saver().save(ref, changes, diff, SaveTarget.InPlace)

        assertThat(outcome).isEqualTo(SaveOutcome.RolledBack((outcome as SaveOutcome.RolledBack).recordId, SaveError.WriteFailed))
        assertThat(h.fileOf(ref.uri).readBytes()).isEqualTo(ORIGINAL)
    }

    @Test
    fun `when even the rollback fails the record keeps its backup and History can restore it`() = runBlocking<Unit> {
        val ref = h.photo("IMG_0004.jpg", ORIGINAL)
        h.documents.corruptNext = 2

        val outcome = h.saver().save(ref, changes, diff, SaveTarget.InPlace)

        assertThat(outcome).isInstanceOf(SaveOutcome.Failed::class.java)
        outcome as SaveOutcome.Failed
        assertThat(outcome.error).isEqualTo(SaveError.RollbackFailed)
        assertThat(outcome.backupKept).isTrue()
        val record = h.dao.record(outcome.recordId!!)!!
        assertThat(record.state).isEqualTo(EditState.Failed)
        assertThat(record.needsRestore).isTrue()
        assertThat(record.canRestore).isTrue()
        assertThat(h.fileOf(ref.uri).readBytes()).isNotEqualTo(ORIGINAL)

        val restore = h.saver().restoreOriginal(record.id)

        assertThat(restore).isInstanceOf(SaveOutcome.Saved::class.java)
        assertThat(h.fileOf(ref.uri).readBytes()).isEqualTo(ORIGINAL)
        assertThat(h.dao.record(record.id)!!.state).isEqualTo(EditState.Restored)
    }

    @Test
    fun `writer failures change nothing and leave no backup`() = runBlocking<Unit> {
        val ref = h.photo("IMG_0005.jpg", ORIGINAL)
        val saver = h.saver()

        h.writer.failWith = FakeWriter.mismatch()
        val mismatch = saver.save(ref, changes, diff, SaveTarget.InPlace) as SaveOutcome.Failed
        h.writer.failWith = UnsupportedEditException("odd layout")
        val unsupported = saver.save(ref, changes, diff, SaveTarget.InPlace) as SaveOutcome.Failed
        h.writer.failWith = null
        h.checkFails = true
        val unreadable = saver.save(ref, changes, diff, SaveTarget.InPlace) as SaveOutcome.Failed

        assertThat(listOf(mismatch.error, unsupported.error, unreadable.error))
            .containsExactly(SaveError.ImageDataChanged, SaveError.UnsupportedEdit, SaveError.CheckFailed)
            .inOrder()
        assertThat(h.fileOf(ref.uri).readBytes()).isEqualTo(ORIGINAL)
        assertThat(h.documents.overwrites).isEqualTo(0)
        assertThat(h.store.files()).isEmpty()
        assertThat(h.dao.observeRecordsOnce().map { it.state }).containsExactly(EditState.Failed, EditState.Failed, EditState.Failed)
        assertThat(h.dao.observeRecordsOnce().none { it.canRestore }).isTrue()
    }

    @Test
    fun `a target that cannot be opened fails without touching it`() = runBlocking<Unit> {
        val ref = h.photo("IMG_0006.jpg", ORIGINAL)
        h.documents.unavailable = true

        val outcome = h.saver().save(ref, changes, diff, SaveTarget.InPlace) as SaveOutcome.Failed

        assertThat(outcome.error).isEqualTo(SaveError.WriteFailed)
        assertThat(h.fileOf(ref.uri).readBytes()).isEqualTo(ORIGINAL)
        assertThat(h.store.files()).isEmpty()
    }

    @Test
    fun `crash after the write completes is recovered as committed`() = runBlocking<Unit> {
        val ref = h.photo("IMG_0007.jpg", ORIGINAL)
        h.documents.crashAfterWrite = true

        assertThrows(SimulatedCrash::class.java) { runBlocking { h.saver().save(ref, changes, diff, SaveTarget.InPlace) } }
        val pending = h.dao.pendingRecords().single()
        assertThat(pending.newSha256).isEqualTo(Sha256.of(edited).sha256)
        assertThat(h.tempDir.listFiles().orEmpty()).isEmpty()

        assertThat(h.saver().recoverInterrupted()).isEqualTo(1)

        val record = h.dao.record(pending.id)!!
        assertThat(record.state).isEqualTo(EditState.Committed)
        assertThat(record.backupPath).isNotNull()
        assertThat(h.fileOf(ref.uri).readBytes()).isEqualTo(edited)
    }

    @Test
    fun `crash in the middle of the write is recovered by writing the backup back`() = runBlocking<Unit> {
        val ref = h.photo("IMG_0008.jpg", ORIGINAL)
        h.documents.crashMidWrite = true

        assertThrows(SimulatedCrash::class.java) { runBlocking { h.saver().save(ref, changes, diff, SaveTarget.InPlace) } }
        assertThat(h.fileOf(ref.uri).readBytes()).isNotEqualTo(ORIGINAL)

        h.saver().recoverInterrupted()

        val record = h.dao.observeRecordsOnce().single()
        assertThat(record.state).isEqualTo(EditState.RolledBack)
        assertThat(record.saveError).isEqualTo(SaveError.Interrupted)
        assertThat(h.fileOf(ref.uri).readBytes()).isEqualTo(ORIGINAL)
        assertThat(record.backupPath).isNull()
        assertThat(h.store.files()).isEmpty()
    }

    @Test
    fun `crash before the target was touched is recovered as rolled back`() = runBlocking<Unit> {
        val ref = h.photo("IMG_0009.jpg", ORIGINAL)
        // The "process dies" after journal, backup and new hash, before the target is opened.
        h.documents.crashBeforeWrite = true

        assertThrows(SimulatedCrash::class.java) { runBlocking { h.saver().save(ref, changes, diff, SaveTarget.InPlace) } }
        assertThat(h.dao.pendingRecords().single().backupPath).isNotNull()

        h.saver().recoverInterrupted()

        val record = h.dao.observeRecordsOnce().single()
        assertThat(record.state).isEqualTo(EditState.RolledBack)
        assertThat(h.fileOf(ref.uri).readBytes()).isEqualTo(ORIGINAL)
        assertThat(h.store.files()).isEmpty()
    }

    @Test
    fun `crash with an unreadable target keeps the backup and marks the record failed`() = runBlocking<Unit> {
        val ref = h.photo("IMG_0010.jpg", ORIGINAL)
        h.documents.crashMidWrite = true
        assertThrows(SimulatedCrash::class.java) { runBlocking { h.saver().save(ref, changes, diff, SaveTarget.InPlace) } }
        // Recovery cannot write the backup back.
        h.documents.unavailable = true

        h.saver().recoverInterrupted()

        val record = h.dao.observeRecordsOnce().single()
        assertThat(record.state).isEqualTo(EditState.Failed)
        assertThat(record.saveError).isEqualTo(SaveError.RecoveryFailed)
        assertThat(record.backupPath).isNotNull()
        assertThat(h.store.file(record.backupPath!!).readBytes()).isEqualTo(ORIGINAL)
        assertThat(record.canRestore).isTrue()
    }

    @Test
    fun `restore puts the original back and can itself be undone`() = runBlocking<Unit> {
        val ref = h.photo("IMG_0011.jpg", ORIGINAL)
        val saver = h.saver()
        val saved = saver.save(ref, changes, diff, SaveTarget.InPlace) as SaveOutcome.Saved

        val restore = saver.restoreOriginal(saved.recordId) as SaveOutcome.Saved

        assertThat(h.fileOf(ref.uri).readBytes()).isEqualTo(ORIGINAL)
        val original = h.dao.record(saved.recordId)!!
        assertThat(original.state).isEqualTo(EditState.Restored)
        assertThat(original.canRestore).isFalse()
        val restoreRecord = h.dao.record(restore.recordId)!!
        assertThat(restoreRecord.restoresRecordId).isEqualTo(saved.recordId)
        assertThat(restoreRecord.state).isEqualTo(EditState.Committed)
        assertThat(FieldDiffCodec.decode(restoreRecord.diffJson).first().before).isEqualTo("Ada")
        // The edited state was backed up before restoring.
        assertThat(h.store.file(restoreRecord.backupPath!!).readBytes()).isEqualTo(edited)
        assertThat(saver.restoreOriginal(saved.recordId)).isEqualTo(SaveOutcome.Rejected(SaveRejection.NotRestorable))

        val undo = saver.restoreOriginal(restore.recordId)

        assertThat(undo).isInstanceOf(SaveOutcome.Saved::class.java)
        assertThat(h.fileOf(ref.uri).readBytes()).isEqualTo(edited)
        assertThat(h.dao.record(restore.recordId)!!.state).isEqualTo(EditState.Restored)
    }

    @Test
    fun `restore refuses a backup that no longer matches its hash`() = runBlocking<Unit> {
        val ref = h.photo("IMG_0012.jpg", ORIGINAL)
        val saver = h.saver()
        val saved = saver.save(ref, changes, diff, SaveTarget.InPlace) as SaveOutcome.Saved
        h.store.file(h.dao.record(saved.recordId)!!.backupPath!!).writeText("tampered")

        val outcome = saver.restoreOriginal(saved.recordId)

        assertThat(outcome).isEqualTo(SaveOutcome.Failed(null, SaveError.BackupDamaged, backupKept = true))
        assertThat(h.fileOf(ref.uri).readBytes()).isEqualTo(edited)
    }

    @Test
    fun `copy writes the edited photo to the destination and leaves the original alone`() = runBlocking<Unit> {
        val ref = h.photo("IMG_0013.jpg", ORIGINAL, writable = false)
        val destination = folder.newFile("IMG_0013 (edited).jpg").toUri().toString()

        val outcome = h.saver().save(ref, changes, diff, SaveTarget.Copy(destination)) as SaveOutcome.Saved

        assertThat(outcome.mode).isEqualTo(EditMode.Copy)
        assertThat(outcome.targetUri).isEqualTo(destination)
        assertThat(h.fileOf(destination).readBytes()).isEqualTo(edited)
        assertThat(h.fileOf(ref.uri).readBytes()).isEqualTo(ORIGINAL)
        val record = h.dao.record(outcome.recordId)!!
        assertThat(record.targetName).isEqualTo("IMG_0013 (edited).jpg")
        assertThat(record.backupPath).isNull()
        assertThat(record.originalSize).isEqualTo(0)
        assertThat(record.canRestore).isFalse()
    }

    @Test
    fun `a copy that fails verification is removed`() = runBlocking<Unit> {
        val ref = h.photo("IMG_0014.jpg", ORIGINAL)
        val destinationFile = folder.newFile("copy.jpg")
        h.documents.corruptNext = 1

        val outcome = h.saver().save(ref, changes, diff, SaveTarget.Copy(destinationFile.toUri().toString()))

        assertThat(outcome).isInstanceOf(SaveOutcome.RolledBack::class.java)
        assertThat(destinationFile.exists()).isFalse()
        assertThat(h.fileOf(ref.uri).readBytes()).isEqualTo(ORIGINAL)
    }

    @Test
    fun `sidecar is created next to a RAW file, named by the setting`() = runBlocking<Unit> {
        val raw = h.photo("DSCF4410.RAF", ORIGINAL)
        val saver = h.saver()

        val first = saver.save(raw, changes, diff, SaveTarget.Sidecar()) as SaveOutcome.Saved
        h.naming = io.github.fishpimp.exiflab.data.settings.SidecarNaming.FullName
        val second = saver.save(raw, changes, diff, SaveTarget.Sidecar()) as SaveOutcome.Saved

        assertThat(h.fileOf(first.targetUri).name).isEqualTo("DSCF4410.xmp")
        assertThat(h.fileOf(second.targetUri).name).isEqualTo("DSCF4410.RAF.xmp")
        assertThat(h.fileOf(first.targetUri).readText()).isEqualTo("<xmp></xmp>")
        assertThat(h.fileOf(raw.uri).readBytes()).isEqualTo(ORIGINAL)
        val record = h.dao.record(first.recordId)!!
        assertThat(record.mode).isEqualTo(EditMode.Sidecar)
        assertThat(record.targetName).isEqualTo("DSCF4410.xmp")
        // There was no sidecar before, so restoring means removing it.
        assertThat(record.backupPath).isNull()
        assertThat(record.canRestore).isTrue()
    }

    @Test
    fun `an existing sidecar is backed up and merged`() = runBlocking<Unit> {
        val raw = h.photo("IMG_1234.CR3", ORIGINAL)
        val existing = h.photos.resolve("IMG_1234.xmp").apply { writeText("<old/>") }

        val outcome = h.saver().save(raw, changes, diff, SaveTarget.Sidecar()) as SaveOutcome.Saved

        assertThat(h.sidecarWriter.existingSeen!!.decodeToString()).isEqualTo("<old/>")
        assertThat(existing.readText()).isEqualTo("<xmp><old/></xmp>")
        val record = h.dao.record(outcome.recordId)!!
        assertThat(h.store.file(record.backupPath!!).readText()).isEqualTo("<old/>")
    }

    @Test
    fun `restoring a new sidecar removes it, and undoing that creates it again`() = runBlocking<Unit> {
        val raw = h.photo("_DSC0912.ARW", ORIGINAL)
        val saver = h.saver()
        val saved = saver.save(raw, changes, diff, SaveTarget.Sidecar()) as SaveOutcome.Saved
        val sidecar = h.fileOf(saved.targetUri)

        val restore = saver.restoreOriginal(saved.recordId) as SaveOutcome.Saved
        assertThat(sidecar.exists()).isFalse()

        saver.restoreOriginal(restore.recordId) as SaveOutcome.Saved
        assertThat(sidecar.readText()).isEqualTo("<xmp></xmp>")
    }

    @Test
    fun `sidecar without a known folder asks for a destination`() = runBlocking<Unit> {
        val raw = h.photo("IMG_9912.CR3", ORIGINAL, inFolder = false)

        val outcome = h.saver().save(raw, changes, diff, SaveTarget.Sidecar())

        assertThat(outcome).isEqualTo(SaveOutcome.NeedsSidecarDestination("IMG_9912.xmp", SIDECAR_MIME_TYPE))
        val picked = folder.newFile("picked.xmp").toUri().toString()
        assertThat(h.saver().save(raw, changes, diff, SaveTarget.Sidecar(picked))).isInstanceOf(SaveOutcome.Saved::class.java)
        assertThat(h.fileOf(picked).readText()).isEqualTo("<xmp></xmp>")
    }

    @Test
    fun `batches count their results and restore as a new batch`() = runBlocking<Unit> {
        val saver = h.saver()
        val batchId = saver.startBatch("Set author", total = 3)
        val a = h.photo("a.jpg", ORIGINAL)
        val b = h.photo("b.jpg", ORIGINAL)
        val c = h.photo("c.jpg", ORIGINAL)
        saver.save(a, changes, diff, SaveTarget.InPlace, batchId)
        saver.save(b, changes, diff, SaveTarget.InPlace, batchId)
        h.writer.failWith = FakeWriter.mismatch()
        saver.save(c, changes, diff, SaveTarget.InPlace, batchId)
        h.writer.failWith = null

        val batch = h.dao.batch(batchId)!!
        assertThat(listOf(batch.total, batch.succeeded, batch.failed)).containsExactly(3, 2, 1).inOrder()

        val result = saver.restoreBatch(batchId)

        assertThat(result.restored).isEqualTo(2)
        assertThat(result.failed).isEqualTo(0)
        assertThat(h.fileOf(a.uri).readBytes()).isEqualTo(ORIGINAL)
        assertThat(h.fileOf(b.uri).readBytes()).isEqualTo(ORIGINAL)
        val restoreBatch = h.dao.batch(result.batchId!!)!!
        assertThat(restoreBatch.restoresBatchId).isEqualTo(batchId)
        assertThat(restoreBatch.succeeded).isEqualTo(2)
        assertThat(h.dao.recordsInBatch(batchId).map { it.state })
            .containsExactly(EditState.Restored, EditState.Restored, EditState.Failed)
    }

    @Test
    fun `saves of the same photo run one at a time`() = runBlocking<Unit> {
        val ref = h.photo("IMG_0015.jpg", ORIGINAL)
        val saver = h.saver()
        h.writer.delayMs = 50

        val outcomes = (1..3).map { async(kotlinx.coroutines.Dispatchers.IO) { saver.save(ref, changes, diff, SaveTarget.InPlace) } }.awaitAll()

        assertThat(outcomes.all { it is SaveOutcome.Saved }).isTrue()
        assertThat(h.writer.maxActive.get()).isEqualTo(1)
        // Each save built on the previous one.
        assertThat(h.fileOf(ref.uri).readText()).isEqualTo(ORIGINAL.decodeToString() + "|edited".repeat(3))
    }

    private suspend fun EditHistoryDao.observeRecordsOnce() = observeRecords().first()
}
