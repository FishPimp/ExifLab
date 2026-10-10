package io.github.fishpimp.exiflab.data.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.metadata.edit.FieldDiff
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The journal DAO on an in-memory database. */
@RunWith(RobolectricTestRunner::class)
class EditHistoryDaoTest {
    private lateinit var database: EditHistoryDatabase
    private lateinit var dao: EditHistoryDao

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EditHistoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.dao()
    }

    @After fun tearDown() = database.close()

    private fun record(
        id: String,
        createdAt: Long,
        state: EditState = EditState.Committed,
        batchId: String? = null,
        backupPath: String? = null,
    ) = EditRecord(
        id = id, batchId = batchId, photoUri = "content://p/$id", displayName = "$id.jpg", mimeType = "image/jpeg",
        photoOrigin = PhotoOrigin.Document, parentDocumentUri = null, mode = EditMode.InPlace, targetUri = null,
        targetName = null, state = state, backupPath = backupPath, originalSize = 10, originalSha256 = "abc",
        newSha256 = null, diffJson = "[]", summary = "Artist", createdAt = createdAt, completedAt = null, error = null,
        restoresRecordId = null,
    )

    @Test
    fun `records and batches come newest first and update live`() = runBlocking<Unit> {
        dao.insertRecord(record("older", createdAt = 1))
        dao.insertRecord(record("newer", createdAt = 2))
        dao.insertBatch(EditBatch("b1", "Batch one", createdAt = 1, total = 2))
        dao.insertBatch(EditBatch("b2", "Batch two", createdAt = 5, total = 1))

        assertThat(dao.observeRecords().first().map { it.id }).containsExactly("newer", "older").inOrder()
        assertThat(dao.observeBatches().first().map { it.id }).containsExactly("b2", "b1").inOrder()

        dao.updateRecord(dao.record("older")!!.copy(state = EditState.Restored))
        assertThat(dao.observeRecord("older").first()!!.state).isEqualTo(EditState.Restored)
        assertThat(dao.observeRecord("missing").first()).isNull()
    }

    @Test
    fun `enums and nullable columns round-trip`() = runBlocking<Unit> {
        val stored = record("r", createdAt = 3).copy(
            mode = EditMode.Sidecar,
            targetUri = "content://p/r.xmp",
            targetName = "r.xmp",
            photoOrigin = PhotoOrigin.Folder,
            parentDocumentUri = "content://tree/doc",
            error = SaveError.RollbackFailed.name,
            state = EditState.Failed,
            newSha256 = "def",
            completedAt = 9,
            restoresRecordId = "x",
        )
        dao.insertRecord(stored)

        assertThat(dao.record("r")).isEqualTo(stored)
        assertThat(dao.record("r")!!.saveError).isEqualTo(SaveError.RollbackFailed)
        assertThat(dao.record("r")!!.destinationUri).isEqualTo("content://p/r.xmp")
    }

    @Test
    fun `pending and backup queries`() = runBlocking<Unit> {
        dao.insertRecord(record("p1", 1, state = EditState.Pending, backupPath = "p1.bak"))
        dao.insertRecord(record("c1", 2, backupPath = "c1.bak"))
        dao.insertRecord(record("c2", 3))

        assertThat(dao.pendingRecords().map { it.id }).containsExactly("p1")
        assertThat(dao.pendingRecordIds()).containsExactly("p1")
        assertThat(dao.recordsWithBackup().map { it.id }).containsExactly("p1", "c1")

        dao.clearBackupPaths(listOf("c1.bak", "unknown.bak"))
        assertThat(dao.recordsWithBackup().map { it.id }).containsExactly("p1")
    }

    @Test
    fun `clearing many backup paths stays within SQLite limits`() = runBlocking<Unit> {
        repeat(1_200) { dao.insertRecord(record("r$it", it.toLong(), backupPath = "r$it.bak")) }

        dao.clearBackupPaths((0 until 1_200).map { "r$it.bak" })

        assertThat(dao.recordsWithBackup()).isEmpty()
    }

    @Test
    fun `batch counters and membership`() = runBlocking<Unit> {
        dao.insertBatch(EditBatch("b", "Location", createdAt = 1, total = 3))
        dao.insertRecord(record("r2", 2, batchId = "b"))
        dao.insertRecord(record("r1", 1, batchId = "b"))
        dao.insertRecord(record("solo", 3))

        dao.countSucceeded("b")
        dao.countSucceeded("b")
        dao.countFailed("b")
        dao.countCancelled("b", 4)

        val batch = dao.batch("b")!!
        assertThat(listOf(batch.succeeded, batch.failed, batch.cancelled)).containsExactly(2, 1, 4).inOrder()
        assertThat(dao.recordsInBatch("b").map { it.id }).containsExactly("r1", "r2").inOrder()
    }

    @Test
    fun `restore commit updates both records together`() = runBlocking<Unit> {
        dao.insertRecord(record("edit", 1))
        dao.insertRecord(record("restore", 2, state = EditState.Pending))

        dao.updateRecords(dao.record("restore")!!.copy(state = EditState.Committed), dao.record("edit")!!.copy(state = EditState.Restored))

        assertThat(dao.record("restore")!!.state).isEqualTo(EditState.Committed)
        assertThat(dao.record("edit")!!.state).isEqualTo(EditState.Restored)
    }

    @Test
    fun `diffs survive the JSON column, and unknown data reads as empty`() {
        val diff = listOf(
            FieldDiff("GPS Latitude", DirectoryGroup.Gps, before = "59.3293", after = null),
            FieldDiff("Artist", DirectoryGroup.Exif, before = null, after = "Ada \"Lovelace\""),
        )
        assertThat(FieldDiffCodec.decode(FieldDiffCodec.encode(diff))).isEqualTo(diff)
        assertThat(FieldDiffCodec.decode("not json")).isEmpty()
        assertThat(FieldDiffCodec.decode("""[{"label":"X","group":"Future","after":"1","extra":true}]""").single().group)
            .isEqualTo(DirectoryGroup.Other)
        assertThat(FieldDiffCodec.invert(diff).first().kind).isEqualTo(FieldDiff.Kind.Added)
    }
}
