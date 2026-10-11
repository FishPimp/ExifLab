package io.github.fishpimp.exiflab.data.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.batch.BatchItemStatus
import io.github.fishpimp.exiflab.data.batch.BatchJob
import io.github.fishpimp.exiflab.data.batch.BatchJobItem
import io.github.fishpimp.exiflab.data.batch.BatchJobState
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Upgrades a database created exactly as the exported version 1 schema describes. Room validates
 * every table against the entities after migrating, so a migration that does not produce the
 * current schema fails here; History written by version 1 must survive.
 */
@RunWith(RobolectricTestRunner::class)
class EditHistoryMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before @After fun clean() {
        context.deleteDatabase(NAME)
    }

    @Test
    fun `version 1 history migrates to version 2 with the batch job tables`() = runBlocking<Unit> {
        createVersion1()

        val database = Room.databaseBuilder(context, EditHistoryDatabase::class.java, NAME)
            .addMigrations(*EditHistoryMigrations.ALL)
            .allowMainThreadQueries()
            .build()
        try {
            val record = database.dao().record("r1")
            assertThat(record).isNotNull()
            assertThat(record!!.state).isEqualTo(EditState.Committed)
            assertThat(record.batchId).isEqualTo("b1")
            assertThat(database.dao().batch("b1")!!.succeeded).isEqualTo(1)
            assertThat(database.openHelper.readableDatabase.version).isEqualTo(2)

            // The new tables work, and deleting a job takes its items along.
            val ref = PhotoRef("content://p/1", "a.jpg", "image/jpeg", 1, 1, PhotoOrigin.Folder, writable = true)
            database.batchJobs().insertJob(
                BatchJob("j1", BatchJobState.Draft, null, "", total = 1, createdAt = 0),
                listOf(BatchJobItem.of("j1", 0, ref)),
            )
            assertThat(database.batchJobs().items("j1").single().status).isEqualTo(BatchItemStatus.Pending)
            assertThat(database.batchJobs().deleteDraftsBefore(1)).isEqualTo(1)
            assertThat(database.batchJobs().items("j1")).isEmpty()
        } finally {
            database.close()
        }
    }

    /** Builds the version 1 tables from the checked-in schema and adds a batch with one record. */
    private fun createVersion1() {
        val schema = Json.parseToJsonElement(File("schemas/${EditHistoryDatabase::class.java.name}/1.json").readText())
            .jsonObject.getValue("database").jsonObject
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(NAME).apply { parentFile?.mkdirs() }, null)
        try {
            schema.getValue("entities").jsonArray.forEach { entity ->
                val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                db.execSQL(entity.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity.jsonObject["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.insertOrThrow(
                "edit_batches",
                null,
                ContentValues().apply {
                    put("id", "b1")
                    put("title", "Set location")
                    put("createdAt", 1L)
                    put("total", 1)
                    put("succeeded", 1)
                    put("failed", 0)
                    put("cancelled", 0)
                },
            )
            db.insertOrThrow(
                "edit_records",
                null,
                ContentValues().apply {
                    put("id", "r1")
                    put("batchId", "b1")
                    put("photoUri", "content://p/1")
                    put("displayName", "a.jpg")
                    put("mimeType", "image/jpeg")
                    put("photoOrigin", "Folder")
                    put("mode", "InPlace")
                    put("state", "Committed")
                    put("backupPath", "r1.bak")
                    put("originalSize", 10L)
                    put("originalSha256", "abc")
                    put("newSha256", "def")
                    put("diffJson", "[]")
                    put("summary", "GPS Latitude")
                    put("createdAt", 1L)
                    put("completedAt", 2L)
                },
            )
            db.version = 1
        } finally {
            db.close()
        }
    }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
