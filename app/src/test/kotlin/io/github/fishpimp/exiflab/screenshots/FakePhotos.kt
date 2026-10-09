package io.github.fishpimp.exiflab.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalInspectionMode
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePainter
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import coil3.compose.asPainter
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import io.github.fishpimp.exiflab.data.folders.FolderAccess
import io.github.fishpimp.exiflab.data.folders.FolderListing
import io.github.fishpimp.exiflab.data.folders.GrantedFolder
import io.github.fishpimp.exiflab.data.folders.LibraryFolder
import io.github.fishpimp.exiflab.data.folders.Subfolder
import io.github.fishpimp.exiflab.data.photos.PhotoFormats
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import kotlin.math.absoluteValue

/** Fake library content for screenshot tests. */
internal object FakePhotos {
    /** Files without a preview, so the format placeholder shows up in screenshots. */
    private val withoutPreview = setOf("IMG_9912.CR3", "scan_004.tif")

    fun photo(name: String, writable: Boolean = true, origin: PhotoOrigin = PhotoOrigin.Folder) = PhotoRef(
        uri = "content://fake.provider/document/$name",
        displayName = name,
        mimeType = PhotoFormats.mimeTypeFromName(name),
        size = 12_345_678,
        lastModified = 1_760_000_000_000,
        origin = origin,
        writable = writable,
    )

    val recents = listOf(
        photo("IMG_2041.HEIC", writable = false, origin = PhotoOrigin.Picker),
        photo("DSCF4410.RAF", writable = false),
        photo("PXL_20260912_081455.jpg"),
        photo("IMG_9912.CR3", writable = false, origin = PhotoOrigin.Document),
        photo("_DSC0912.ARW"),
    )

    val folders = listOf(
        LibraryFolder(GrantedFolder("content://fake.provider/tree/camera", "Camera", 0), FolderAccess.ReadWrite),
        LibraryFolder(GrantedFolder("content://fake.provider/tree/lofoten", "Lofoten 2026", 0), FolderAccess.ReadOnly),
        LibraryFolder(GrantedFolder("content://fake.provider/tree/sd", "SD card exports", 0), FolderAccess.Lost),
    )

    val listing = FolderListing(
        subfolders = listOf(
            Subfolder("content://fake.provider/tree/camera/document/edits", "Edits"),
            Subfolder("content://fake.provider/tree/camera/document/weekend", "Long weekend in the archipelago"),
        ),
        photos = listOf(
            photo("DSCF4410.RAF", writable = false),
            photo("IMG_2041.HEIC"),
            photo("PXL_20260912_081455.jpg"),
            photo("_DSC0912.ARW", writable = false),
            photo("IMG_0007.DNG", writable = false),
            photo("Screenshot_20260901.png"),
            photo("IMG_9912.CR3", writable = false),
            photo("export_final.webp"),
            photo("scan_004.tif"),
            photo("IMG_2042.HEIC"),
            photo("DSCF4411.RAF", writable = false),
            photo("PXL_20260913_190012.jpg"),
        ),
    )

    /** Photo-like gradients (sky over ground), picked per file so neighbours differ. */
    private val palettes = listOf(
        intArrayOf(0xFFFFB86B.toInt(), 0xFFE8618C.toInt(), 0xFF3A2E5C.toInt()),
        intArrayOf(0xFF9ED8F0.toInt(), 0xFF3C8DBC.toInt(), 0xFF0F3B57.toInt()),
        intArrayOf(0xFFD9EAD3.toInt(), 0xFF6AA84F.toInt(), 0xFF274E13.toInt()),
        intArrayOf(0xFFF3F6FA.toInt(), 0xFFB4C5D9.toInt(), 0xFF5B6B7F.toInt()),
        intArrayOf(0xFFFFE599.toInt(), 0xFFC27C0E.toInt(), 0xFF4A2C0A.toInt()),
    )

    fun previewBitmap(ref: PhotoRef): Bitmap? {
        if (ref.displayName in withoutPreview) return null
        val colors = palettes[ref.uri.hashCode().absoluteValue % palettes.size]
        val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val sky = Paint().apply { shader = LinearGradient(0f, 0f, 0f, 120f, colors, null, Shader.TileMode.CLAMP) }
        canvas.drawRect(0f, 0f, 160f, 120f, sky)
        val hill = Paint().apply { color = colors[2] }
        canvas.drawCircle(40f, 150f, 70f, hill)
        canvas.drawCircle(130f, 160f, 80f, hill)
        return bitmap
    }
}

/**
 * Serves [FakePhotos.previewBitmap] to every Coil image inside [content], synchronously, so
 * thumbnails appear in screenshots. Files without a preview fail like a real RAW without one.
 */
@OptIn(ExperimentalCoilApi::class)
@Composable
internal fun WithFakeThumbnails(content: @Composable () -> Unit) {
    val handler = remember {
        AsyncImagePreviewHandler { _, request ->
            val bitmap = (request.data as? PhotoRef)?.let(FakePhotos::previewBitmap)
            if (bitmap != null) {
                val image = bitmap.asImage()
                AsyncImagePainter.State.Success(image.asPainter(request.context), SuccessResult(image, request))
            } else {
                AsyncImagePainter.State.Error(null, ErrorResult(null, request, IllegalStateException("No preview")))
            }
        }
    }
    CompositionLocalProvider(
        LocalInspectionMode provides true,
        LocalAsyncImagePreviewHandler provides handler,
        content = content,
    )
}
