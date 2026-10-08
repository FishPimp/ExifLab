package io.github.fishpimp.exiflab.core.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Placeholder entity; the real schema (history, backups, journal, batches) lands with the data layer. */
@Entity(tableName = "history")
public data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val description: String,
)

@Dao
public interface HistoryDao {
    @Insert
    public suspend fun insert(entity: HistoryEntity): Long

    @Query("SELECT * FROM history ORDER BY createdAt DESC")
    public fun observeAll(): Flow<List<HistoryEntity>>
}

@Database(entities = [HistoryEntity::class], version = 1, exportSchema = true)
public abstract class ExifLabDatabase : RoomDatabase() {
    public abstract fun historyDao(): HistoryDao
}
