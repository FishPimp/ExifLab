package io.github.fishpimp.exiflab.data.shareclean

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.metadata.ByteArrayImageSource
import io.github.fishpimp.exiflab.metadata.EmbeddedPreview
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.ImageSource
import io.github.fishpimp.exiflab.metadata.Metadata
import io.github.fishpimp.exiflab.metadata.MetadataReader
import io.github.fishpimp.exiflab.metadata.PreviewExtractor
import io.github.fishpimp.exiflab.metadata.edit.Editing
import io.github.fishpimp.exiflab.metadata.edit.StripCategory
import io.github.fishpimp.exiflab.metadata.model.LocationStatus
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.PhotoSummary
import io.github.fishpimp.exiflab.metadata.model.Rational
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory
import io.github.fishpimp.exiflab.metadata.write.ExifChange
import io.github.fishpimp.exiflab.metadata.write.ExifIfd
import io.github.fishpimp.exiflab.metadata.write.ExifValue
import io.github.fishpimp.exiflab.metadata.write.MetadataChanges
import io.github.fishpimp.exiflab.metadata.write.MetadataWriter
import io.github.fishpimp.exiflab.metadata.write.MetadataWriting
import io.github.fishpimp.exiflab.metadata.write.WriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * The Share clean pipeline with the real planner, writer and reader on encoded images: what the
 * preset covers is gone from the copy, the image data is untouched, and a copy that still holds
 * removed metadata is refused.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareCleanerTest {
    @get:Rule val folder = TemporaryFolder()

    private val files = mutableMapOf<String, Pair<ByteArray, String>>()
    private val store by lazy { SharedFilesStore(folder.root.resolve("shared")) }

    private fun encode(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(200, 90, 40))
        return ByteArrayOutputStream().also { bitmap.compress(format, 90, it) }.toByteArray()
    }

    /** GPS position, two serial numbers, make and model, an artist and a capture date. */
    private val privateDetails = MetadataChanges(
        exif = listOf(
            ExifChange.Set(ExifIfd.Primary, 0x010F, ExifValue.Ascii("Fujifilm")),
            ExifChange.Set(ExifIfd.Primary, 0x0110, ExifValue.Ascii("X-T5")),
            ExifChange.Set(ExifIfd.Primary, 0x013B, ExifValue.Ascii("Maja Lindqvist")),
            ExifChange.Set(ExifIfd.Primary, 0x0112, ExifValue.Shorts(listOf(6))),
            ExifChange.Set(ExifIfd.Exif, 0x9003, ExifValue.Ascii("2026:06:14 17:42:07")),
            ExifChange.Set(ExifIfd.Exif, 0xA431, ExifValue.Ascii("5CA12345")),
            ExifChange.Set(ExifIfd.Exif, 0xA435, ExifValue.Ascii("LS-998877")),
            ExifChange.Set(ExifIfd.Gps, 0x0001, ExifValue.Ascii("N")),
            ExifChange.Set(ExifIfd.Gps, 0x0002, ExifValue.Rationals(listOf(Rational(59, 1), Rational(19, 1), Rational(4512, 100)))),
            ExifChange.Set(ExifIfd.Gps, 0x0003, ExifValue.Ascii("E")),
            ExifChange.Set(ExifIfd.Gps, 0x0004, ExifValue.Rationals(listOf(Rational(18, 1), Rational(4, 1), Rational(1830, 100)))),
        ),
    )

    /** An encoded image carrying [privateDetails]. */
    private fun privatePhoto(format: Bitmap.CompressFormat, name: String): ByteArray {
        val plain = encode(format)
        val out = ByteArrayOutputStream()
        MetadataWriting.writer.write(ByteArrayImageSource(plain, name), privateDetails, out)
        return out.toByteArray().also { assertThat(read(it, name).locationStatus).isEqualTo(LocationStatus.Present) }
    }

    private fun photo(name: String, bytes: ByteArray, mimeType: String = "image/jpeg"): PhotoRef {
        val uri = "content://test/${files.size}/$name"
        files[uri] = bytes to name
        return PhotoRef(uri, name, mimeType, bytes.size.toLong(), null, PhotoOrigin.Picker, writable = false)
    }

    private fun read(bytes: ByteArray, name: String): MetadataReport = Metadata.reader.read(ByteArrayImageSource(bytes, name))

    private fun digest(bytes: ByteArray, name: String) = MetadataWriting.digest.digest(ByteArrayImageSource(bytes, name))

    private fun MetadataReport.tag(name: String) = directories.flatMap { it.tags }.firstOrNull { it.name == name }?.displayValue

    private fun MetadataReport.has(category: SensitivityCategory) = sensitiveFindings.any { it.category == category }

    private fun cleaner(
        reader: MetadataReader = Metadata.reader,
        writer: MetadataWriter = MetadataWriting.writer,
        previews: PreviewExtractor = Metadata.previewExtractor,
    ) = ShareCleaner(
        open = { ref -> files.getValue(ref.uri).let { (bytes, name) -> ByteArrayImageSource(bytes, name) } },
        reader = { reader },
        planner = { Editing.planner },
        writer = { writer },
        previews = { previews },
        store = store,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun clean(photos: List<PhotoRef>, choice: ShareCleanChoice, cleaner: ShareCleaner = cleaner()) =
        runBlocking { cleaner.clean(photos, choice) { _, _ -> } }

    @Test
    fun `location and device removes GPS, serials and camera, keeps the image data and the artist`() {
        val original = privatePhoto(Bitmap.CompressFormat.JPEG, "DSCF0001.JPG")
        val result = clean(listOf(photo("DSCF0001.JPG", original)), ShareCleanChoice(ShareCleanPreset.LocationAndDevice))

        assertThat(result.refusals).isEmpty()
        val copy = result.files.single()
        assertThat(copy.file.name).isEqualTo("DSCF0001.JPG")
        assertThat(copy.file.parentFile!!.parentFile).isEqualTo(store.root)
        assertThat(copy.mimeType).isEqualTo("image/jpeg")
        val bytes = copy.file.readBytes()
        assertThat(digest(bytes, "DSCF0001.JPG")).isEqualTo(digest(original, "DSCF0001.JPG"))

        val report = read(bytes, "DSCF0001.JPG")
        assertThat(report.locationStatus).isNotEqualTo(LocationStatus.Present)
        assertThat(report.has(SensitivityCategory.Location)).isFalse()
        assertThat(report.has(SensitivityCategory.SerialNumber)).isFalse()
        assertThat(report.tag("Make")).isNull()
        assertThat(report.tag("Model")).isNull()
        // Not covered by this preset.
        assertThat(report.tag("Artist")).isEqualTo("Maja Lindqvist")
        assertThat(report.summary.capturedAt).isNotNull()
    }

    @Test
    fun `everything leaves only the orientation in a PNG`() {
        val original = privatePhoto(Bitmap.CompressFormat.PNG, "scan.png")
        val result = clean(listOf(photo("scan.png", original, "image/png")), ShareCleanChoice(ShareCleanPreset.Everything))

        val copy = result.files.single()
        assertThat(copy.mimeType).isEqualTo("image/png")
        val bytes = copy.file.readBytes()
        assertThat(digest(bytes, "scan.png")).isEqualTo(digest(original, "scan.png"))
        val report = read(bytes, "scan.png")
        assertThat(report.sensitiveFindings).isEmpty()
        assertThat(report.tag("Artist")).isNull()
        assertThat(report.summary.capturedAt).isNull()
        assertThat(report.summary.orientation).isEqualTo(6)
        assertThat(MetadataInventory.of(report, setOf(StripCategory.All)).isEmpty).isTrue()
    }

    @Test
    fun `a copy that still holds removed metadata is refused and deleted`() {
        // A writer that "forgets" the changes: the copy is the original, image data intact.
        val forgetful = object : MetadataWriter {
            override fun canWrite(format: ImageFormat) = MetadataWriting.writer.canWrite(format)
            override fun write(source: ImageSource, changes: MetadataChanges, output: OutputStream): WriteResult {
                val bytes = source.open().use { it.readBytes() }
                output.write(bytes)
                val digest = MetadataWriting.digest.digest(source)
                return WriteResult(ImageFormat.Jpeg, bytes.size.toLong(), digest, digest)
            }
        }
        val ref = photo("IMG_0002.jpg", privatePhoto(Bitmap.CompressFormat.JPEG, "IMG_0002.jpg"))

        val result = clean(listOf(ref), ShareCleanChoice(ShareCleanPreset.LocationAndDevice), cleaner(writer = forgetful))

        assertThat(result.files).isEmpty()
        val refusal = result.refusals.single()
        assertThat(refusal.name).isEqualTo("IMG_0002.jpg")
        val reason = refusal.reason as RefusalReason.StillContains
        assertThat(reason.kinds).containsAtLeast(MetadataKind.Location, MetadataKind.SerialNumbers, MetadataKind.CameraDetails).inOrder()
        assertThat(store.root.walkTopDown().filter { it.isFile }.toList()).isEmpty()
    }

    @Test
    fun `a RAW file is shared as its preview, without metadata but upright`() {
        val preview = privatePhoto(Bitmap.CompressFormat.JPEG, "preview.jpg")
        val rawReport = MetadataReport(
            format = ImageFormat.Nef,
            fileName = "DSC_0001.NEF",
            fileSize = 1_000,
            summary = PhotoSummary(orientation = 8),
            location = null,
            locationStatus = LocationStatus.Absent,
            directories = emptyList(),
            sensitiveFindings = emptyList(),
            warnings = emptyList(),
        )
        val rawAwareReader = object : MetadataReader {
            override fun read(source: ImageSource) =
                if (source.fileName == "DSC_0001.NEF") rawReport else Metadata.reader.read(source)
        }
        val extractor = object : PreviewExtractor {
            override fun extract(source: ImageSource, maxBytes: Int) = EmbeddedPreview(preview, 64, 48, orientation = 8)
        }
        val ref = photo("DSC_0001.NEF", byteArrayOf(0x49, 0x49, 0x2A, 0x00), "image/x-nikon-nef")

        val shared = clean(listOf(ref), ShareCleanChoice(ShareCleanPreset.LocationOnly), cleaner(reader = rawAwareReader, previews = extractor))
        val copy = shared.files.single()
        assertThat(copy.fromRawPreview).isTrue()
        assertThat(copy.file.name).isEqualTo("DSC_0001.jpg")
        assertThat(copy.mimeType).isEqualTo("image/jpeg")
        val report = read(copy.file.readBytes(), copy.file.name)
        assertThat(report.sensitiveFindings).isEmpty()
        assertThat(report.tag("Make")).isNull()
        assertThat(report.summary.orientation).isEqualTo(8)
        assertThat(digest(copy.file.readBytes(), "a.jpg")).isEqualTo(digest(preview, "a.jpg"))

        val leftOut = clean(
            listOf(ref),
            ShareCleanChoice(ShareCleanPreset.LocationOnly, includeRawPreviews = false),
            cleaner(reader = rawAwareReader, previews = extractor),
        )
        assertThat(leftOut.files).isEmpty()
        assertThat(leftOut.refusals.single().reason).isEqualTo(RefusalReason.RawLeftOut)

        val noPreview = object : PreviewExtractor {
            override fun extract(source: ImageSource, maxBytes: Int): EmbeddedPreview? = null
        }
        val missing = clean(listOf(ref), ShareCleanChoice(), cleaner(reader = rawAwareReader, previews = noPreview))
        assertThat(missing.refusals.single().reason).isEqualTo(RefusalReason.NoRawPreview)
    }

    @Test
    fun `files that are not images are left out, the rest is shared in order with progress`() {
        val first = photo("a.jpg", privatePhoto(Bitmap.CompressFormat.JPEG, "a.jpg"))
        val notes = photo("notes.txt", "just some text, no image here".toByteArray(), "text/plain")
        val last = photo("b.png", privatePhoto(Bitmap.CompressFormat.PNG, "b.png"), "image/png")
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = runBlocking {
            cleaner().clean(listOf(first, notes, last), ShareCleanChoice(ShareCleanPreset.LocationOnly)) { done, total -> progress += done to total }
        }

        assertThat(result.files.map { it.sourceName }).containsExactly("a.jpg", "b.png").inOrder()
        assertThat(result.refusals.single().name).isEqualTo("notes.txt")
        assertThat(result.refusals.single().reason).isInstanceOf(RefusalReason.Unsupported::class.java)
        assertThat(progress).containsExactly(1 to 3, 2 to 3, 3 to 3).inOrder()
    }

    @Test
    fun `copy names are safe and keep matching extensions`() {
        assertThat(ShareCleaner.fileNameFor("IMG_1.JPG", ImageFormat.Jpeg, keepExtension = true)).isEqualTo("IMG_1.JPG")
        assertThat(ShareCleaner.fileNameFor("../../evil/name.png", ImageFormat.Png, keepExtension = true)).isEqualTo("_.._evil_name.png")
        assertThat(ShareCleaner.fileNameFor("DSC_0001.NEF", ImageFormat.Jpeg, keepExtension = false)).isEqualTo("DSC_0001.jpg")
        assertThat(ShareCleaner.fileNameFor("photo.jpg", ImageFormat.Heif, keepExtension = true)).isEqualTo("photo.heic")
        assertThat(ShareCleaner.fileNameFor("", ImageFormat.WebP, keepExtension = true)).isEqualTo("photo.webp")
    }
}
