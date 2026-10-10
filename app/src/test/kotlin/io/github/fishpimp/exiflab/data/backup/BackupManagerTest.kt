package io.github.fishpimp.exiflab.data.backup

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.history.EditMode
import io.github.fishpimp.exiflab.data.history.EditRecord
import io.github.fishpimp.exiflab.data.history.EditState
import io.github.fishpimp.exiflab.data.history.SaveError
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.save.SaveTestHarness
import io.github.fishpimp.exiflab.data.settings.BackupRetention
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/** Pruning and deletion against the journal and real files. */
@RunWith(RobolectricTestRunner::class)
class BackupManagerTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var h: SaveTestHarness
    private val now = 1_800_000_000_000L
    private var retention = BackupRetention.Days30

    @Before fun setUp() {
        h = SaveTestHarness(folder.root)
    }

    @After fun tearDown() = h.close()

    private fun manager(cap: Long = Long.MAX_VALUE) =
        BackupManager(h.dao, h.store, retention = { retention }, sizeCapBytes = cap, clock = { now })

    private suspend fun recordWithBackup(
        id: String,
        ageDays: Int,
        bytes: Int = 100,
        photo: String = id,
        state: EditState = EditState.Committed,
        error: SaveError? = null,
    ): EditRecord {
        val stored = h.store.create(id, ByteArray(bytes) { 7 }.inputStream())
        val record = EditRecord(
            id = id, batchId = null, photoUri = "content://photos/$photo", displayName = "$photo.jpg", mimeType = "image/jpeg",
            photoOrigin = PhotoOrigin.Folder, parentDocumentUri = null, mode = EditMode.InPlace, targetUri = null, targetName = null,
            state = state, backupPath = stored.path, originalSize = bytes.toLong(), originalSha256 = stored.hash.sha256,
            newSha256 = null, diffJson = "[]", summary = "", createdAt = now - TimeUnit.DAYS.toMillis(ageDays.toLong()),
            completedAt = null, error = error?.name, restoresRecordId = null,
        )
        h.dao.insertRecord(record)
        return record
    }

    @Test
    fun `prune deletes expired backups and forgets them in the journal`() = runBlocking<Unit> {
        recordWithBackup("old", ageDays = 40)
        recordWithBackup("new", ageDays = 2)

        val result = manager().prune()

        assertThat(result.deletedFiles).isEqualTo(1)
        assertThat(result.freedBytes).isEqualTo(100)
        assertThat(h.dao.record("old")!!.backupPath).isNull()
        assertThat(h.dao.record("old")!!.canRestore).isFalse()
        assertThat(h.dao.record("new")!!.backupPath).isNotNull()
        assertThat(h.store.files().map { it.path }).containsExactly("new.bak")
        assertThat(h.store.usedBytes.value).isEqualTo(100)
    }

    @Test
    fun `prune keeps backups of pending saves and of failed rollbacks`() = runBlocking<Unit> {
        recordWithBackup("pending", ageDays = 400, state = EditState.Pending)
        recordWithBackup("damaged", ageDays = 400, state = EditState.Failed, error = SaveError.RollbackFailed)
        recordWithBackup("old", ageDays = 400)
        retention = BackupRetention.Days7

        manager(cap = 0).prune()

        assertThat(h.store.files().map { it.path }).containsExactly("pending.bak", "damaged.bak")
    }

    @Test
    fun `prune applies the size cap but keeps the newest backup of each photo`() = runBlocking<Unit> {
        recordWithBackup("a1", ageDays = 5, photo = "a")
        recordWithBackup("a2", ageDays = 3, photo = "a")
        recordWithBackup("b1", ageDays = 4, photo = "b")
        retention = BackupRetention.Forever

        manager(cap = 150).prune()

        assertThat(h.store.files().map { it.path }).containsExactly("a2.bak", "b1.bak")
    }

    @Test
    fun `prune removes leftovers no record needs, but not a backup being written`() = runBlocking<Unit> {
        h.store.create("orphan", "x".toByteArray().inputStream())
        h.store.directory.resolve("crashed.bak.partial").writeText("half")
        // A save that has journaled its record and is writing its backup right now.
        recordWithBackup("busy", ageDays = 0, state = EditState.Pending)
        h.dao.updateRecord(h.dao.record("busy")!!.copy(backupPath = null))

        manager().prune()

        assertThat(h.store.files().map { it.path }).containsExactly("busy.bak")
        assertThat(h.store.partialFiles()).isEmpty()
    }

    @Test
    fun `delete all keeps only backups of saves in progress`() = runBlocking<Unit> {
        recordWithBackup("pending", ageDays = 0, state = EditState.Pending)
        recordWithBackup("done", ageDays = 1)
        recordWithBackup("damaged", ageDays = 1, state = EditState.Failed, error = SaveError.RollbackFailed)

        val result = manager().deleteAll()

        assertThat(result.deletedFiles).isEqualTo(2)
        assertThat(h.store.files().map { it.path }).containsExactly("pending.bak")
        assertThat(h.dao.record("done")!!.backupPath).isNull()
        assertThat(h.dao.record("damaged")!!.backupPath).isNull()
        assertThat(h.dao.record("pending")!!.backupPath).isEqualTo("pending.bak")
    }

    @Test
    fun `delete backup removes one record's backup, never a pending one`() = runBlocking<Unit> {
        recordWithBackup("done", ageDays = 1)
        recordWithBackup("pending", ageDays = 0, state = EditState.Pending)
        val manager = manager()

        assertThat(manager.deleteBackup("done")).isTrue()
        assertThat(manager.deleteBackup("pending")).isFalse()
        assertThat(manager.deleteBackup("missing")).isFalse()

        assertThat(h.dao.record("done")!!.backupPath).isNull()
        assertThat(h.store.files().map { it.path }).containsExactly("pending.bak")
    }

    @Test
    fun `a backup deleted outside the app is forgotten`() = runBlocking<Unit> {
        recordWithBackup("gone", ageDays = 1)
        h.store.file("gone.bak").delete()

        manager().prune()

        assertThat(h.dao.record("gone")!!.backupPath).isNull()
    }
}
