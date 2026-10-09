package io.github.fishpimp.exiflab.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePainter
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import coil3.compose.asPainter
import coil3.request.SuccessResult
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.designsystem.theme.BrandPalette
import io.github.fishpimp.exiflab.designsystem.theme.ThemeMode
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory
import io.github.fishpimp.exiflab.ui.photo.PhotoActions
import io.github.fishpimp.exiflab.ui.photo.PhotoContent
import io.github.fishpimp.exiflab.ui.photo.PhotoLoad
import io.github.fishpimp.exiflab.ui.photo.PhotoLoadError
import io.github.fishpimp.exiflab.ui.photo.PhotoUiState
import io.github.fishpimp.exiflab.ui.photo.TagBrowserBuilder
import io.github.fishpimp.exiflab.ui.photo.ValueMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The photo viewer with realistic reports: a phone JPEG with location, a Fujifilm RAW with
 * MakerNotes, serials and names, and a bare PNG screenshot. Tall windows capture more than one
 * screen of the scrolling content.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test @Config(qualifiers = "w411dp-h2300dp-xxhdpi")
    fun phone_light() = compose.snapshot("photo_phone_light") { Viewer(FakeReports.phoneRef, FakeReports.phoneJpeg) }

    @Test @Config(qualifiers = "w411dp-h2300dp-night-xxhdpi")
    fun phone_dark() = compose.snapshot("photo_phone_dark", ThemeMode.Dark, BrandPalette.Iris) {
        Viewer(FakeReports.phoneRef, FakeReports.phoneJpeg)
    }

    @Test @Config(qualifiers = "sv-w411dp-h2300dp-xxhdpi")
    fun raw_swedish() = compose.snapshot("photo_raw_sv", palette = BrandPalette.Citrus) { Viewer(FakeReports.rawRef, FakeReports.cameraRaw) }

    @Test @Config(qualifiers = "w411dp-h4200dp-xxhdpi", fontScale = 2.0f)
    fun phone_large_font() = compose.snapshot("photo_phone_font200") { Viewer(FakeReports.phoneRef, FakeReports.phoneJpeg) }

    @Test @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun raw_mode() = compose.snapshot("photo_raw_mode", palette = BrandPalette.Moss) {
        Viewer(FakeReports.rawRef, FakeReports.cameraRaw, mode = ValueMode.Raw, scrolledToTags = true)
    }

    @Test @Config(qualifiers = "w411dp-h1000dp-xxhdpi")
    fun search() = compose.snapshot("photo_search") {
        Viewer(FakeReports.phoneRef, FakeReports.phoneJpeg, query = "exposure", scrolledToTags = true)
    }

    @Test @Config(qualifiers = "w411dp-h1100dp-night-xxhdpi")
    fun sensitivity_filter() = compose.snapshot("photo_filter", ThemeMode.Dark, BrandPalette.Ember) {
        Viewer(FakeReports.rawRef, FakeReports.cameraRaw, filter = SensitivityCategory.SerialNumber, scrolledToTags = true)
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun tablet_two_pane() = compose.snapshot("photo_tablet", palette = BrandPalette.Iris) { Viewer(FakeReports.rawRef, FakeReports.cameraRaw) }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-night-xhdpi")
    fun tablet_two_pane_phone() = compose.snapshot("photo_tablet_phone", ThemeMode.Dark) {
        Viewer(FakeReports.phoneRef, FakeReports.phoneJpeg, query = "gps")
    }

    @Test @Config(qualifiers = "w700dp-h1600dp-xhdpi")
    fun medium_width() = compose.snapshot("photo_medium", palette = BrandPalette.Graphite) {
        Viewer(FakeReports.phoneRef, FakeReports.phoneJpeg)
    }

    @Test @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun location_redacted() = compose.snapshot("photo_location_redacted") {
        Viewer(FakeReports.phonePickerRef, FakeReports.phoneJpegRedacted)
    }

    @Test @Config(qualifiers = "w411dp-h2300dp-xxhdpi")
    fun bare_png() = compose.snapshot("photo_png_shared", palette = BrandPalette.Citrus) { Viewer(FakeReports.pngRef, FakeReports.barePng) }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun loading() = compose.snapshot("photo_loading") {
        PhotoContent(FakeReports.phoneRef, PhotoUiState(load = PhotoLoad.Loading), query = "", actions = PhotoActions())
    }

    @Test @Config(qualifiers = "sv-w411dp-h891dp-xxhdpi")
    fun error_access_lost() = compose.snapshot("photo_error_sv", palette = BrandPalette.Ember) {
        PhotoContent(FakeReports.phonePickerRef, PhotoUiState(load = PhotoLoad.Failed(PhotoLoadError.AccessLost)), query = "", actions = PhotoActions())
    }

    @Composable
    private fun Viewer(
        ref: PhotoRef,
        report: MetadataReport,
        query: String = "",
        mode: ValueMode = ValueMode.Readable,
        filter: SensitivityCategory? = null,
        scrolledToTags: Boolean = false,
    ) = WithFakePhotos {
        val filtering = query.isNotBlank() || filter != null
        val collapsed = if (filtering) emptySet() else TagBrowserBuilder.defaultCollapsed(report)
        val state = PhotoUiState(
            load = PhotoLoad.Loaded(report),
            browser = TagBrowserBuilder.build(report, query, mode, filter, collapsed),
            mode = mode,
            filter = filter,
        )
        PhotoContent(
            ref = ref,
            state = state,
            query = query,
            actions = PhotoActions(),
            listState = rememberLazyListState(initialFirstVisibleItemIndex = if (scrolledToTags) 1 else 0),
        )
    }
}

/** Serves large synthetic photos (a landscape, or a phone screenshot for PNGs) to every Coil image. */
@OptIn(ExperimentalCoilApi::class)
@Composable
private fun WithFakePhotos(content: @Composable () -> Unit) {
    val handler = remember {
        AsyncImagePreviewHandler { _, request ->
            val ref = request.data as? PhotoRef
            val bitmap = if (ref?.mimeType == "image/png") screenshotBitmap() else landscapeBitmap(ref?.uri.orEmpty())
            val image = bitmap.asImage()
            AsyncImagePainter.State.Success(image.asPainter(request.context), SuccessResult(image, request))
        }
    }
    CompositionLocalProvider(
        LocalInspectionMode provides true,
        LocalAsyncImagePreviewHandler provides handler,
        content = content,
    )
}

/** A 4:3 or 3:2 evening landscape: sky gradient, sun, layered hills and water. */
private fun landscapeBitmap(seed: String): Bitmap {
    val width = 960
    val height = if (seed.endsWith(".RAF")) 640 else 720
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val raw = seed.endsWith(".RAF")
    val sky = if (raw) intArrayOf(0xFF1E3A5F.toInt(), 0xFF5B7DB1.toInt(), 0xFFF2C38B.toInt()) else
        intArrayOf(0xFF2B1E4F.toInt(), 0xFFB0507A.toInt(), 0xFFFFB46B.toInt())
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, height * 0.7f, sky, null, Shader.TileMode.CLAMP)
    })
    canvas.drawCircle(width * 0.68f, height * 0.52f, height * 0.09f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE3A3.toInt() })
    val hills = if (raw) listOf(0xFF34506E.toInt(), 0xFF22384F.toInt(), 0xFF14222F.toInt()) else
        listOf(0xFF6D3B5E.toInt(), 0xFF4A2A48.toInt(), 0xFF2A1730.toInt())
    hills.forEachIndexed { index, color ->
        val base = height * (0.58f + index * 0.08f)
        val path = Path().apply {
            moveTo(0f, base)
            var x = 0f
            var up = index % 2 == 0
            while (x <= width) {
                x += width / 6f
                quadTo(x - width / 12f, base + if (up) -height * 0.08f else height * 0.03f, x, base)
                up = !up
            }
            lineTo(width.toFloat(), height.toFloat())
            lineTo(0f, height.toFloat())
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }
    return bitmap
}

/** A portrait phone screenshot: status bar, a header card and list rows. */
private fun screenshotBitmap(): Bitmap {
    val width = 540
    val height = 1200
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(0xFFF4F1FA.toInt())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = 0xFF6750A4.toInt()
    canvas.drawRoundRect(RectF(32f, 120f, width - 32f, 420f), 48f, 48f, paint)
    paint.color = 0xFFE8DEF8.toInt()
    repeat(6) { row ->
        val top = 470f + row * 112f
        canvas.drawRoundRect(RectF(32f, top, width - 32f, top + 92f), 28f, 28f, paint)
    }
    paint.color = 0xFF1D1B20.toInt()
    canvas.drawRect(0f, 0f, width.toFloat(), 56f, paint)
    return bitmap
}
