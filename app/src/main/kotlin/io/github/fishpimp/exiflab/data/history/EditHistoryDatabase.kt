package io.github.fishpimp.exiflab.data.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.fishpimp.exiflab.data.batch.BatchJob
import io.github.fishpimp.exiflab.data.batch.BatchJobDao
import io.github.fishpimp.exiflab.data.batch.BatchJobItem

/**
 * The edit journal and history. Schemas are exported to `app/schemas`; every version bump needs
 * a migration in [EditHistoryMigrations] (history and backup references must survive updates, so
 * there is no destructive fallback).
 */
@Database(
    entities = [EditBatch::class, EditRecord::class, BatchJob::class, BatchJobItem::class],
    version = 3,
    exportSchema = true,
)
abstract class EditHistoryDatabase : RoomDatabase() {
    abstract fun dao(): EditHistoryDao

    /** Batch edit jobs and their per-photo status (since version 2). */
    abstract fun batchJobs(): BatchJobDao

    companion object {
        const val NAME = "edit_history.db"

        fun create(context: Context): EditHistoryDatabase =
            Room.databaseBuilder(context.applicationContext, EditHistoryDatabase::class.java, NAME)
                .addMigrations(*EditHistoryMigrations.ALL)
                .build()
    }
}

/** Hand-written migrations between schema versions, oldest first. */
object EditHistoryMigrations {
    /** Version 2 adds batch jobs (M5): `batch_jobs` and their `batch_job_items`. History is untouched. */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `batch_jobs` (`id` TEXT NOT NULL, `state` TEXT NOT NULL, `operationJson` TEXT, " +
                    "`title` TEXT NOT NULL, `total` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `startedAt` INTEGER, " +
                    "`finishedAt` INTEGER, `editBatchId` TEXT, `copyFolderUri` TEXT, `cancelRequested` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_batch_jobs_state` ON `batch_jobs` (`state`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `batch_job_items` (`jobId` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                    "`photoUri` TEXT NOT NULL, `displayName` TEXT, `mimeType` TEXT, `size` INTEGER, `lastModified` INTEGER, " +
                    "`origin` TEXT NOT NULL, `writable` INTEGER NOT NULL, `parentDocumentUri` TEXT, `status` TEXT NOT NULL, " +
                    "`reason` TEXT, `saveError` TEXT, `recordId` TEXT, `targetUri` TEXT, `finishedAt` INTEGER, " +
                    "PRIMARY KEY(`jobId`, `position`), FOREIGN KEY(`jobId`) REFERENCES `batch_jobs`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_batch_job_items_jobId_status` ON `batch_job_items` (`jobId`, `status`)")
        }
    }

    /** Version 3 keeps the writer's notes for batch photos written without every change (`notes`). */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `batch_job_items` ADD COLUMN `notes` TEXT")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
