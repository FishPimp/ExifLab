package io.github.fishpimp.exiflab.data.save

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.history.EditMode
import io.github.fishpimp.exiflab.data.history.EditState
import io.github.fishpimp.exiflab.metadata.ByteArrayImageSource
import io.github.fishpimp.exiflab.metadata.Metadata
import io.github.fishpimp.exiflab.metadata.edit.FieldDiff
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.LocationStatus
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.Rational
import io.github.fishpimp.exiflab.metadata.write.ExifChange
import io.github.fishpimp.exiflab.metadata.write.ExifIfd
import io.github.fishpimp.exiflab.metadata.write.ExifValue
import io.github.fishpimp.exiflab.metadata.write.MetadataChanges
import io.github.fishpimp.exiflab.metadata.write.MetadataWriting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end saves through [PhotoSaver] with the real metadata writer, digest and reader, on
 * real encoded images: the edit lands, the image data stays byte-identical, and restore brings
 * back the exact original.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RealEngineSaveTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var h: SaveTestHarness
    private val ids = AtomicInteger()

    @Before fun setUp() {
        h = SaveTestHarness(folder.root)
    }

    @After fun tearDown() = h.close()

    private fun saver() = PhotoSaver(
        dao = h.dao,
        backups = h.store,
        documents = h.documents,
        engine = WriteEngine.default(),
        tempDirectory = h.tempDir,
        sidecarNaming = { h.naming },
        afterSave = {},
        clock = { 1_760_000_000_000L + ids.get() },
        newId = { "real-${ids.incrementAndGet()}" },
        ioDispatcher = Dispatchers.IO,
    )

    private fun encode(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(30, 140, 150))
        return ByteArrayOutputStream().also { bitmap.compress(format, 90, it) }.toByteArray()
    }

    private val authorAndPlace = MetadataChanges(
        exif = listOf(
            ExifChange.Set(ExifIfd.Primary, 0x013B, ExifValue.Ascii("Ada Lovelace")),
            ExifChange.Set(ExifIfd.Gps, 0x0001, ExifValue.Ascii("N")),
            ExifChange.Set(ExifIfd.Gps, 0x0002, ExifValue.Rationals(listOf(Rational(59, 1), Rational(19, 1), Rational(4512, 100)))),
            ExifChange.Set(ExifIfd.Gps, 0x0003, ExifValue.Ascii("E")),
            ExifChange.Set(ExifIfd.Gps, 0x0004, ExifValue.Rationals(listOf(Rational(18, 1), Rational(4, 1), Rational(1830, 100)))),
        ),
    )
    private val diff = listOf(FieldDiff("Artist", DirectoryGroup.Exif, null, "Ada Lovelace"))

    private fun read(bytes: ByteArray, name: String): MetadataReport = Metadata.reader.read(ByteArrayImageSource(bytes, name))

    private fun digest(bytes: ByteArray, name: String) = MetadataWriting.digest.digest(ByteArrayImageSource(bytes, name))

    private fun MetadataReport.tag(name: String) = directories.flatMap { it.tags }.firstOrNull { it.name == name }?.displayValue

    @Test
    fun `in place JPEG save writes the change, keeps image data and restores exactly`() = runBlocking<Unit> {
        val original = encode(Bitmap.CompressFormat.JPEG)
        val ref = h.photo("IMG_1000.jpg", original)

        val outcome = saver().save(ref, authorAndPlace, diff, SaveTarget.InPlace)

        assertThat(outcome).isInstanceOf(SaveOutcome.Saved::class.java)
        val edited = h.fileOf(ref.uri).readBytes()
        assertThat(edited).isNotEqualTo(original)
        assertThat(digest(edited, "IMG_1000.jpg")).isEqualTo(digest(original, "IMG_1000.jpg"))
        val report = read(edited, "IMG_1000.jpg")
        assertThat(report.tag("Artist")).isEqualTo("Ada Lovelace")
        assertThat(report.locationStatus).isEqualTo(LocationStatus.Present)
        assertThat(report.location!!.latitude).isWithin(1e-4).of(59.3292)
        assertThat(report.location!!.longitude).isWithin(1e-4).of(18.0717)

        val record = h.dao.record((outcome as SaveOutcome.Saved).recordId)!!
        assertThat(record.state).isEqualTo(EditState.Committed)
        assertThat(h.store.file(record.backupPath!!).readBytes()).isEqualTo(original)

        assertThat(saver().restoreOriginal(record.id)).isInstanceOf(SaveOutcome.Saved::class.java)
        assertThat(h.fileOf(ref.uri).readBytes()).isEqualTo(original)
    }

    @Test
    fun `copy of a PNG gets the change while the original stays untouched`() = runBlocking<Unit> {
        val original = encode(Bitmap.CompressFormat.PNG)
        val ref = h.photo("scan.png", original, writable = false)
        val copy = h.photos.resolve("scan (edited).png").apply { createNewFile() }

        val outcome = saver().save(ref, authorAndPlace, diff, SaveTarget.Copy(copy.toURI().toString().replace("file:/", "file:///")))

        assertThat(outcome).isInstanceOf(SaveOutcome.Saved::class.java)
        assertThat((outcome as SaveOutcome.Saved).mode).isEqualTo(EditMode.Copy)
        assertThat(h.fileOf(ref.uri).readBytes()).isEqualTo(original)
        val edited = copy.readBytes()
        assertThat(digest(edited, "scan.png")).isEqualTo(digest(original, "scan.png"))
        assertThat(read(edited, "scan.png").tag("Artist")).isEqualTo("Ada Lovelace")
    }

    @Test
    fun `RAW files get an XMP sidecar next to them`() = runBlocking<Unit> {
        val raw = byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00) + ByteArray(64)
        val ref = h.photo("DSC_0001.NEF", raw)

        val outcome = saver().save(ref, authorAndPlace, diff, SaveTarget.Sidecar())

        assertThat(outcome).isInstanceOf(SaveOutcome.Saved::class.java)
        assertThat(h.fileOf(ref.uri).readBytes()).isEqualTo(raw)
        val sidecar = h.photos.resolve("DSC_0001.xmp").readText()
        assertThat(sidecar).contains("Ada Lovelace")
        assertThat(sidecar).contains("GPSLatitude")
    }
}
