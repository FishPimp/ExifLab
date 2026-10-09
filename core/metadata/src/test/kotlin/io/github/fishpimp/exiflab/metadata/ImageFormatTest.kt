package io.github.fishpimp.exiflab.metadata

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImageFormatTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ascii(text: String, padTo: Int = ImageFormat.SNIFF_LENGTH) =
        text.toByteArray(Charsets.ISO_8859_1).copyOf(padTo)

    @Test fun jpeg() = assertThat(ImageFormat.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE1))).isEqualTo(ImageFormat.Jpeg)

    @Test fun png() = assertThat(ImageFormat.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))).isEqualTo(ImageFormat.Png)

    @Test fun webp() = assertThat(ImageFormat.sniff(ascii("RIFF\u0000\u0000\u0000\u0000WEBPVP8X"))).isEqualTo(ImageFormat.WebP)

    @Test fun heic() = assertThat(ImageFormat.sniff(ascii("\u0000\u0000\u0000\u0018ftypheic"))).isEqualTo(ImageFormat.Heif)

    @Test fun avifByBrand() = assertThat(ImageFormat.sniff(ascii("\u0000\u0000\u0000\u001cftypavif"))).isEqualTo(ImageFormat.Avif)

    @Test fun cr3() = assertThat(ImageFormat.sniff(ascii("\u0000\u0000\u0000\u0018ftypcrx "))).isEqualTo(ImageFormat.Cr3)

    @Test fun raf() = assertThat(ImageFormat.sniff(ascii("FUJIFILMCCD-RAW 0201"))).isEqualTo(ImageFormat.Raf)

    @Test fun cr2() = assertThat(ImageFormat.sniff(bytes(0x49, 0x49, 0x2A, 0x00, 0x10, 0, 0, 0, 0x43, 0x52, 0x02, 0x00))).isEqualTo(ImageFormat.Cr2)

    @Test fun tiffBasedRawUsesExtension() {
        val tiff = bytes(0x4D, 0x4D, 0x00, 0x2A, 0, 0, 0, 8)
        assertThat(ImageFormat.sniff(tiff, "DSC_0001.NEF")).isEqualTo(ImageFormat.Nef)
        assertThat(ImageFormat.sniff(tiff, "IMG_0001.dng")).isEqualTo(ImageFormat.Dng)
        assertThat(ImageFormat.sniff(tiff, "scan.tif")).isEqualTo(ImageFormat.Tiff)
        assertThat(ImageFormat.sniff(tiff, null)).isEqualTo(ImageFormat.Tiff)
    }

    @Test fun rawFlag() {
        assertThat(ImageFormat.Dng.isRaw).isTrue()
        assertThat(ImageFormat.Jpeg.isRaw).isFalse()
    }

    @Test fun unknown() = assertThat(ImageFormat.sniff(bytes(0x00, 0x01))).isEqualTo(ImageFormat.Unknown)
}
