package io.github.fishpimp.exiflab.data.batch

import io.github.fishpimp.exiflab.metadata.edit.EditOperation
import io.github.fishpimp.exiflab.metadata.edit.StripCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.ZoneId

/** The batch actions offered for a selection. */
enum class BatchKind { Location, ShiftTime, TimeZone, Author, Strip }

/**
 * The language-independent title a batch is stored under, in its job and in History, so it is
 * shown in the app's current language instead of the one it was started in.
 */
val BatchKind.storedTitle: String get() = "batch-kind:$name"

/** The kind a stored batch title stands for; null for the text older versions stored. */
fun batchKindOfTitle(title: String): BatchKind? = BatchKind.entries.firstOrNull { it.storedTitle == title }

/**
 * What a batch does to every photo, stored with the [BatchJob] as JSON so an interrupted job can
 * resume with exactly the same edit. Each maps to one [EditOperation] that the planner plans
 * against every photo's own metadata.
 */
@Serializable
sealed interface BatchOperation {
    val kind: BatchKind

    fun toEditOperation(): EditOperation

    @Serializable @SerialName("location")
    data class SetLocation(val latitude: Double, val longitude: Double, val altitudeMeters: Double? = null) : BatchOperation {
        override val kind: BatchKind get() = BatchKind.Location
        override fun toEditOperation() = EditOperation.SetLocation(latitude, longitude, altitudeMeters)
    }

    /** Moves every date by [seconds] (negative is earlier). */
    @Serializable @SerialName("shift")
    data class ShiftTime(val seconds: Long) : BatchOperation {
        override val kind: BatchKind get() = BatchKind.ShiftTime
        override fun toEditOperation() = EditOperation.ShiftCaptureTime(Duration.ofSeconds(seconds))
    }

    /** Re-reads local times recorded in [cameraZone] as [actualZone] times (zone ids). */
    @Serializable @SerialName("timezone")
    data class FixTimeZone(val cameraZone: String, val actualZone: String) : BatchOperation {
        override val kind: BatchKind get() = BatchKind.TimeZone
        override fun toEditOperation() = EditOperation.ChangeTimeZone(ZoneId.of(cameraZone), ZoneId.of(actualZone))
    }

    /** Sets the artist and/or copyright; a half that is not changed keeps each photo's own value. */
    @Serializable @SerialName("author")
    data class SetAuthorship(
        val artist: String?,
        val copyright: String?,
        val changeArtist: Boolean = true,
        val changeCopyright: Boolean = true,
    ) : BatchOperation {
        override val kind: BatchKind get() = BatchKind.Author
        override fun toEditOperation() = EditOperation.SetAuthorship(artist, copyright, changeArtist, changeCopyright)
    }

    /** Removes the [StripCategory] names in [categories]. */
    @Serializable @SerialName("strip")
    data class Strip(val categories: List<String>) : BatchOperation {
        override val kind: BatchKind get() = BatchKind.Strip

        val stripCategories: Set<StripCategory>
            get() = categories.mapNotNull { name -> StripCategory.entries.firstOrNull { it.name == name } }.toSet()

        override fun toEditOperation() = EditOperation.Strip(stripCategories)

        companion object {
            fun of(categories: Set<StripCategory>) = Strip(StripCategory.entries.filter { it in categories }.map { it.name })
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(operation: BatchOperation): String = json.encodeToString(serializer(), operation)

        fun decodeOrNull(text: String): BatchOperation? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}
