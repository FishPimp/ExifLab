package io.github.fishpimp.exiflab.thumbnails

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Size
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.metadata.EmbeddedPreview
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RawThumbnailPipelineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val raw = PhotoRef(
        uri = "content://test.provider/raw/1",
        displayName = "DSCF0001.RAF",
        mimeType = "image/x-fuji-raf",
        size = 4_000,
        lastModified = 1_700_000_000_000,
        origin = PhotoOrigin.Folder,
        writable = false,
    )
    private var extractions = 0
    private var preview: EmbeddedPreview? = null
    private lateinit var imageLoader: ImageLoader

    @Before
    fun setUp() {
        imageLoader = photoImageLoader(context) {
            extractions++
            preview
        }
    }

    @After
    fun tearDown() {
        imageLoader.diskCache?.clear()
        imageLoader.shutdown()
    }

    @Test
    fun `RAW preview is rotated by the container orientation and sampled to size`() = runBlocking {
        preview = EmbeddedPreview(landscapeJpeg(width = 400, height = 200), 400, 200, ExifInterface.ORIENTATION_ROTATE_90)

        val bitmap = load(raw, Size(50, 100))

        assertThat(bitmap.width).isEqualTo(50)
        assertThat(bitmap.height).isEqualTo(100)
        // The left edge of the landscape preview was red; after a 90 degree turn it is the top.
        assertThat(Color.red(bitmap.getPixel(25, 2))).isGreaterThan(200)
    }

    @Test
    fun `preview is extracted once, then served from the disk cache`() = runBlocking {
        preview = EmbeddedPreview(landscapeJpeg(width = 40, height = 20), 40, 20, null)

        val first = fetch(raw)
        val second = fetch(raw)

        assertThat(extractions).isEqualTo(1)
        assertThat(first.mimeType).isEqualTo("image/jpeg")
        assertThat(second.dataSource).isEqualTo(DataSource.DISK)
        assertThat(second.source.metadata).isInstanceOf(RawPreviewMetadata::class.java)
        second.source.close()
        first.source.close()
    }

    @Test
    fun `a changed file gets a fresh preview`() = runBlocking {
        preview = EmbeddedPreview(landscapeJpeg(width = 40, height = 20), 40, 20, null)

        fetch(raw).source.close()
        fetch(raw.copy(lastModified = raw.lastModified!! + 1)).source.close()

        assertThat(extractions).isEqualTo(2)
    }

    @Test
    fun `memory cache key changes with the file`() {
        val keyer = PhotoRefKeyer()
        val options = Options(context)
        assertThat(keyer.key(raw, options)).isNotEqualTo(keyer.key(raw.copy(size = 1), options))
    }

    private suspend fun fetch(ref: PhotoRef, size: Size = Size.ORIGINAL): SourceFetchResult {
        val options = Options(context, size = size)
        val fetcher = PhotoRefFetcher.Factory { extractions++; preview }.create(ref, options, imageLoader)
        return fetcher.fetch() as SourceFetchResult
    }

    private suspend fun load(ref: PhotoRef, size: Size): Bitmap {
        val options = Options(context, size = size)
        val result = fetch(ref, size)
        val decoder = checkNotNull(RawPreviewDecoder.Factory().create(result, options, imageLoader))
        return result.source.use { (checkNotNull(decoder.decode()).image as BitmapImage).bitmap }
    }

    /** A JPEG whose left half is red and right half blue. */
    private fun landscapeJpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLUE)
        canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { color = Color.RED })
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    }
}
