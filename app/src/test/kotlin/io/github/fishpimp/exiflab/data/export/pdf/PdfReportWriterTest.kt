package io.github.fishpimp.exiflab.data.export.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.materialkolor.dynamicColorScheme
import io.github.fishpimp.exiflab.data.export.ExportFormat
import io.github.fishpimp.exiflab.data.export.ExportProgress
import io.github.fishpimp.exiflab.data.export.ExportSettings
import io.github.fishpimp.exiflab.data.export.ExportStage
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.metadata.model.GeoLocation
import io.github.fishpimp.exiflab.screenshots.FakeReports
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate

/** The PDF writer end to end: both passes, summary, page numbers, images; fake photos, thumbnails and maps. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfReportWriterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val palette = PdfPalette.from(dynamicColorScheme(Color(0xFF1E8C93), isDark = false), isDark = false, sensitiveColor = Color(0xFFD9480F))
    private val broken = PhotoRef("content://fake/broken.jpg", "broken.jpg", "image/jpeg", 10, null, PhotoOrigin.Folder, writable = true)
    private val reports = mapOf(FakeReports.phoneRef.uri to FakeReports.phoneJpeg, FakeReports.rawRef.uri to FakeReports.cameraRaw)

    private val thumbnailRequests = mutableListOf<Pair<PhotoRef, Int>>()
    private val mapRequests = mutableListOf<GeoLocation>()

    private fun writer(mapMode: PdfMapMode = PdfMapMode.Map, mapWorks: Boolean = true) = PdfReportWriter(
        context = context,
        readReport = { ref -> reports[ref.uri] ?: throw IOException("unreadable") },
        thumbnails = { ref, size ->
            thumbnailRequests += ref to size
            Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        },
        maps = object : PdfMaps {
            override suspend fun mode() = mapMode

            override suspend fun render(location: GeoLocation, width: Int, height: Int, palette: PdfPalette): Bitmap? {
                mapRequests += location
                return if (mapWorks) Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) else null
            }
        },
        today = { LocalDate.of(2026, 10, 12) },
        newSink = ::RecordingSink,
    )

    private fun ByteArray.pageCount(): Int = Regex("/Type /Page\n").findAll(String(this, Charsets.ISO_8859_1)).count()

    /**
     * Robolectric has no native PdfDocument, so pages are drawn onto a bitmap with the same
     * painter, and the "file" lists them in PDF-like text.
     */
    private class RecordingSink : PdfPageSink {
        private val numbers = mutableListOf<Int>()

        override fun page(width: Int, height: Int, number: Int, draw: (Canvas) -> Unit) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            draw(Canvas(bitmap))
            numbers += number
        }

        override fun writeTo(output: OutputStream) {
            output.write(buildString {
                append("%PDF-recorded\n")
                numbers.forEach { append("/Type /Page\n") }
            }.toByteArray(Charsets.ISO_8859_1))
        }

        override fun close() {
            assertThat(numbers).isEqualTo((1..numbers.size).toList())
        }
    }

    @Test fun singlePhotoReport() = runBlocking {
        val out = ByteArrayOutputStream()
        val progress = mutableListOf<ExportProgress>()
        val result = writer().write(listOf(FakeReports.phoneRef), ExportSettings(format = ExportFormat.Pdf), palette, out) { progress += it }
        assertThat(result.exported).isEqualTo(1)
        assertThat(result.failed).isEmpty()
        val bytes = out.toByteArray()
        assertThat(String(bytes, 0, 5, Charsets.ISO_8859_1)).isEqualTo("%PDF-")
        assertThat(bytes.pageCount()).isAtLeast(2)
        assertThat(thumbnailRequests.single()).isEqualTo(FakeReports.phoneRef to PdfReportWriter.SINGLE_THUMBNAIL_PIXELS)
        assertThat(mapRequests).hasSize(1)
        assertThat(progress.last()).isEqualTo(ExportProgress(1, 1))
    }

    @Test fun severalPhotosGetASummaryAndOneSectionEach() = runBlocking {
        val out = ByteArrayOutputStream()
        val progress = mutableListOf<ExportProgress>()
        val photos = listOf(FakeReports.phoneRef, broken, FakeReports.rawRef)
        val result = writer().write(photos, ExportSettings(format = ExportFormat.Pdf), palette, out) { progress += it }
        assertThat(result.exported).isEqualTo(2)
        assertThat(result.failed).containsExactly("broken.jpg")

        // Same page count as laying the report out directly.
        val layout = PdfReportLayout(PdfPainter(PdfFonts.load(context), palette))
        val builder = PdfSectionBuilder(context, ExportSettings(format = ExportFormat.Pdf))
        val phonePages = layout.layoutPhoto(builder.section(FakeReports.PHONE_NAME, FakeReports.phoneJpeg, PdfMapMode.Map)).size
        val rawPages = layout.layoutPhoto(builder.section(FakeReports.RAW_NAME, FakeReports.cameraRaw, PdfMapMode.Map)).size
        assertThat(out.toByteArray().pageCount()).isEqualTo(1 + phonePages + rawPages)

        // Thumbnails and maps only in the drawing pass, smaller for many photos; the RAW file has no location.
        assertThat(thumbnailRequests.map { it.second }.toSet()).containsExactly(PdfReportWriter.MULTI_THUMBNAIL_PIXELS)
        assertThat(thumbnailRequests).hasSize(2)
        assertThat(mapRequests).hasSize(1)
        assertThat(progress.first().stage).isEqualTo(ExportStage.Preparing)
        assertThat(progress.last()).isEqualTo(ExportProgress(2, 2, ExportStage.Writing, 1f))
        assertThat(progress.map { it.fraction }).isInOrder()
    }

    @Test fun aFailedMapKeepsThePageCount() = runBlocking {
        val withMap = ByteArrayOutputStream()
        val withoutMap = ByteArrayOutputStream()
        val photos = listOf(FakeReports.phoneRef, FakeReports.rawRef)
        writer(mapWorks = true).write(photos, ExportSettings(), palette, withMap) {}
        writer(mapWorks = false).write(photos, ExportSettings(), palette, withoutMap) {}
        assertThat(withoutMap.toByteArray().pageCount()).isEqualTo(withMap.toByteArray().pageCount())
    }

    @Test fun noMapWhenOfflineOrRedacted() = runBlocking {
        writer(mapMode = PdfMapMode.Offline).write(listOf(FakeReports.phoneRef), ExportSettings(), palette, ByteArrayOutputStream()) {}
        writer().write(listOf(FakeReports.phoneRef), ExportSettings(redactSensitive = true).effective, palette, ByteArrayOutputStream()) {}
        writer().write(listOf(FakeReports.phoneRef), ExportSettings(includeMap = false), palette, ByteArrayOutputStream()) {}
        assertThat(mapRequests).isEmpty()
    }

    @Test fun unreadablePhotoWritesNothing() = runBlocking {
        val out = ByteArrayOutputStream()
        val result = writer().write(listOf(broken), ExportSettings(), palette, out) {}
        assertThat(result.exported).isEqualTo(0)
        assertThat(result.failed).containsExactly("broken.jpg")
        assertThat(out.size()).isEqualTo(0)
    }
}
