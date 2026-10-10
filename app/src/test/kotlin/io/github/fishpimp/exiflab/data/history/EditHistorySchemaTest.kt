package io.github.fishpimp.exiflab.data.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The checked-in schema in `app/schemas` must describe the entities as they are: a change to an
 * entity needs a version bump, an exported schema and a migration, or History is lost on update.
 */
@RunWith(RobolectricTestRunner::class)
class EditHistorySchemaTest {
    @Test
    fun `exported schema of the current version matches the entities`() {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EditHistoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val liveHash = database.openHelper.readableDatabase.query("SELECT identity_hash FROM room_master_table").use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }
        val version = database.openHelper.readableDatabase.version
        database.close()

        val exported = File("schemas/${EditHistoryDatabase::class.java.name}/$version.json")
        assertThat(exported.isFile).isTrue()
        val schema = Json.parseToJsonElement(exported.readText()).jsonObject.getValue("database").jsonObject
        assertThat(schema.getValue("version").jsonPrimitive.int).isEqualTo(version)
        assertThat(schema.getValue("identityHash").jsonPrimitive.content).isEqualTo(liveHash)
    }

    @Test
    fun `every older version has a migration`() {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EditHistoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val version = database.openHelper.readableDatabase.version
        database.close()

        val covered = EditHistoryMigrations.ALL.map { it.startVersion to it.endVersion }.toSet()
        (1 until version).forEach { from -> assertThat(covered).contains(from to from + 1) }
    }
}
