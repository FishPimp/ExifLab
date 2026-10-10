package io.github.fishpimp.exiflab.data.save

import android.content.Context
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.fishpimp.exiflab.data.backup.BackupStore
import io.github.fishpimp.exiflab.data.history.EditHistoryDao
import io.github.fishpimp.exiflab.data.history.EditHistoryDatabase
import io.github.fishpimp.exiflab.data.photos.PhotoFormats
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.settings.SidecarNaming
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.ImageSource
import io.github.fishpimp.exiflab.metadata.edit.FieldDiff
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.write.ExifChange
import io.github.fishpimp.exiflab.metadata.write.ExifIfd
import io.github.fishpimp.exiflab.metadata.write.ExifValue
import io.github.fishpimp.exiflab.metadata.write.ImageDataMismatchException
import io.github.fishpimp.exiflab.metadata.write.MetadataChanges
import io.github.fishpimp.exiflab.metadata.write.MetadataWriter
import io.github.fishpimp.exiflab.metadata.write.SidecarWriter
import io.github.fishpimp.exiflab.metadata.write.WriteResult
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/** Thrown by [FaultyDocuments] to simulate the process dying; nothing in the pipeline catches it. */
class SimulatedCrash : Error("Simulated crash")

/**
 * Everything [PhotoSaver] needs, on real files (`file://` URIs through the real
 * [ContentResolverDocuments]), an in-memory journal and fake metadata writers.
 */
class SaveTestHarness(root: File) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val database: EditHistoryDatabase = Room.inMemoryDatabaseBuilder(context, EditHistoryDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val dao: EditHistoryDao = database.dao()
    val photos: File = root.resolve("photos").apply { mkdirs() }
    val backupDir: File = root.resolve("backups")
    val tempDir: File = root.resolve("cache/saving")
    val store = BackupStore(backupDir)
    val documents = FaultyDocuments(ContentResolverDocuments(context.contentResolver))
    val writer = FakeWriter()
    val sidecarWriter = FakeSidecarWriter()
    var checkFails = false
    var naming = SidecarNaming.BaseName
    var now = 1_760_000_000_000L
    private val ids = AtomicInteger()
    val committedSaves = AtomicInteger()

    val engine = WriteEngine(
        writer = { writer },
        sidecar = { sidecarWriter },
        check = { _, _ -> if (checkFails) error("Cannot parse") },
    )

    /** A fresh saver over the same files and journal, like the app after a restart. */
    fun saver() = PhotoSaver(
        dao = dao,
        backups = store,
        documents = documents,
        engine = engine,
        tempDirectory = tempDir,
        sidecarNaming = { naming },
        afterSave = { committedSaves.incrementAndGet() },
        clock = { now++ },
        newId = { "record-${ids.incrementAndGet()}" },
        ioDispatcher = Dispatchers.IO,
    )

    fun photo(name: String, bytes: ByteArray, writable: Boolean = true, inFolder: Boolean = true): PhotoRef {
        val file = photos.resolve(name)
        file.writeBytes(bytes)
        return PhotoRef(
            uri = file.toUri().toString(),
            displayName = name,
            mimeType = PhotoFormats.mimeTypeFromName(name),
            size = bytes.size.toLong(),
            lastModified = null,
            origin = PhotoOrigin.Folder,
            writable = writable,
            parentDocumentUri = if (inFolder) photos.toUri().toString() else null,
        )
    }

    fun fileOf(uri: String): File = File(requireNotNull(uri.toUri().path))

    fun close() = database.close()

    companion object {
        val ORIGINAL = "JPEG-IMAGE-DATA|exif:old".toByteArray()
        val changes = MetadataChanges(exif = listOf(ExifChange.Set(ExifIfd.Primary, 0x013B, ExifValue.Ascii("Ada"))))
        val diff = listOf(
            FieldDiff("Artist", DirectoryGroup.Exif, before = null, after = "Ada"),
            FieldDiff("Date Taken", DirectoryGroup.Exif, before = "2026:01:01 10:00:00", after = "2026:01:01 11:00:00"),
        )
    }
}

/** Appends `|edited` to the source, like a writer that rewrote a metadata segment. */
class FakeWriter : MetadataWriter {
    var failWith: Exception? = null
    var formats = setOf(ImageFormat.Jpeg, ImageFormat.Png)
    val active = AtomicInteger()
    val maxActive = AtomicInteger()
    var delayMs = 0L

    override fun canWrite(format: ImageFormat) = format in formats

    override fun write(source: ImageSource, changes: MetadataChanges, output: OutputStream): WriteResult {
        val running = active.incrementAndGet()
        maxActive.accumulateAndGet(running) { a, b -> maxOf(a, b) }
        try {
            if (delayMs > 0) Thread.sleep(delayMs)
            failWith?.let { throw it }
            val bytes = source.open().use(InputStream::readBytes) + "|edited".toByteArray()
            output.write(bytes)
            return WriteResult(ImageFormat.Jpeg, bytes.size.toLong(), "digest", "digest", skipped = listOf("IPTC"))
        } finally {
            active.decrementAndGet()
        }
    }

    companion object {
        fun mismatch() = ImageDataMismatchException("image data changed")
    }
}

/** Writes `<xmp>` plus the previous sidecar's content, so merging is visible. */
class FakeSidecarWriter : SidecarWriter {
    var existingSeen: ByteArray? = null

    override fun write(existingSidecar: ByteArray?, changes: MetadataChanges, output: OutputStream) {
        existingSeen = existingSidecar
        output.write("<xmp>".toByteArray())
        existingSidecar?.let(output::write)
        output.write("</xmp>".toByteArray())
    }
}

/** Wraps real file access and injects failures into [overwrite]. */
class FaultyDocuments(private val real: DocumentAccess) : DocumentAccess by real {
    /** The next N overwrites land with their last byte flipped, as with a faulty provider. */
    var corruptNext = 0

    /** Fails the next N overwrites after writing [corrupt]ed content, as a broken provider would. */
    var failAfterWriting = 0

    /** Every overwrite fails before opening the target. */
    var unavailable = false

    /** The next overwrite "dies" before it opens the target. */
    var crashBeforeWrite = false

    /** The next overwrite writes only half the content, then the "process dies". */
    var crashMidWrite = false

    /** The next overwrite completes, then the "process dies" before the read-back check. */
    var crashAfterWrite = false

    /** Number of overwrites so far. */
    var overwrites = 0

    override fun overwrite(uri: String, write: (OutputStream) -> Unit) {
        overwrites++
        if (unavailable) throw TargetUnavailableException("unavailable")
        if (crashBeforeWrite) {
            crashBeforeWrite = false
            throw SimulatedCrash()
        }
        val buffer = java.io.ByteArrayOutputStream()
        write(buffer)
        var bytes = buffer.toByteArray()
        if (corruptNext > 0 && bytes.isNotEmpty()) {
            corruptNext--
            bytes = bytes.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte() }
        }
        when {
            crashMidWrite -> {
                crashMidWrite = false
                real.overwrite(uri) { it.write(bytes, 0, bytes.size / 2) }
                throw SimulatedCrash()
            }
            crashAfterWrite -> {
                crashAfterWrite = false
                real.overwrite(uri) { it.write(bytes) }
                throw SimulatedCrash()
            }
            failAfterWriting > 0 -> {
                failAfterWriting--
                real.overwrite(uri) { it.write(bytes, 0, bytes.size / 2) }
                throw java.io.IOException("provider failed")
            }
            else -> real.overwrite(uri) { it.write(bytes) }
        }
    }
}
