package io.github.fishpimp.exiflab.data.photos

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.ImageFormat
import org.junit.Test

class PhotoFormatsTest {
    @Test
    fun `extension wins over a generic MIME type`() {
        assertThat(PhotoFormats.formatOf("application/octet-stream", "DSCF0001.RAF")).isEqualTo(ImageFormat.Raf)
        assertThat(PhotoFormats.formatOf("image/tiff", "IMG_0001.NEF")).isEqualTo(ImageFormat.Nef)
    }

    @Test
    fun `MIME type is the fallback, aliases included`() {
        assertThat(PhotoFormats.formatOf("image/heic", "photo")).isEqualTo(ImageFormat.Heif)
        assertThat(PhotoFormats.formatOf("image/jpeg; charset=binary", null)).isEqualTo(ImageFormat.Jpeg)
        assertThat(PhotoFormats.formatOf("image/x-adobe-dng", null)).isEqualTo(ImageFormat.Dng)
        assertThat(PhotoFormats.formatOf("text/plain", "notes")).isEqualTo(ImageFormat.Unknown)
    }

    @Test
    fun `supported images are image MIME types or known extensions`() {
        assertThat(PhotoFormats.isSupportedImage("image/gif", "anim.gif")).isTrue()
        assertThat(PhotoFormats.isSupportedImage("application/octet-stream", "IMG_0001.CR3")).isTrue()
        assertThat(PhotoFormats.isSupportedImage("application/octet-stream", "backup.zip")).isFalse()
        assertThat(PhotoFormats.isSupportedImage(null, null)).isFalse()
    }

    @Test
    fun `format label prefers the extension the user sees`() {
        assertThat(PhotoFormats.formatLabel("image/heif", "IMG_1234.heic")).isEqualTo("HEIC")
        assertThat(PhotoFormats.formatLabel("image/jpeg", "IMG_1234.JPG")).isEqualTo("JPG")
        assertThat(PhotoFormats.formatLabel("image/x-fuji-raf", "unnamed")).isEqualTo("RAF")
        assertThat(PhotoFormats.formatLabel("image/gif", null)).isEqualTo("GIF")
        assertThat(PhotoFormats.formatLabel(null, "README")).isNull()
    }

    @Test
    fun `document picker offers images, every RAW type and generic files`() {
        val types = PhotoFormats.documentPickerMimeTypes.toList()
        assertThat(types).containsAtLeast("image/*", "image/x-adobe-dng", "image/x-canon-cr3", "image/x-sony-arw", "application/octet-stream")
        assertThat(types).containsNoDuplicates()
    }

    @Test
    fun `PhotoRef helpers use name and MIME type`() {
        val raw = ref(name = "DSC_0001.NEF", mime = "application/octet-stream")
        assertThat(raw.isRaw).isTrue()
        assertThat(raw.formatLabel).isEqualTo("NEF")
        assertThat(ref(name = "IMG.png", mime = "image/png").isRaw).isFalse()
    }

    private fun ref(name: String, mime: String) = PhotoRef(
        uri = "content://test/$name",
        displayName = name,
        mimeType = mime,
        size = 1,
        lastModified = 1,
        origin = PhotoOrigin.Document,
        writable = false,
    )
}
