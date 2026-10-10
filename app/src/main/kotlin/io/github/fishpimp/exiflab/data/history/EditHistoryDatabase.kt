package io.github.fishpimp.exiflab.data.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * The edit journal and history. Schemas are exported to `app/schemas`; every version bump needs
 * a migration in [EditHistoryMigrations] (history and backup references must survive updates, so
 * there is no destructive fallback).
 */
@Database(entities = [EditBatch::class, EditRecord::class], version = 1, exportSchema = true)
abstract class EditHistoryDatabase : RoomDatabase() {
    abstract fun dao(): EditHistoryDao

    companion object {
        const val NAME = "edit_history.db"

        fun create(context: Context): EditHistoryDatabase =
            Room.databaseBuilder(context.applicationContext, EditHistoryDatabase::class.java, NAME)
                .addMigrations(*EditHistoryMigrations.ALL)
                .build()
    }
}

/** Hand-written migrations between schema versions, oldest first. Empty while there is only version 1. */
object EditHistoryMigrations {
    val ALL: Array<Migration> = emptyArray()
}
