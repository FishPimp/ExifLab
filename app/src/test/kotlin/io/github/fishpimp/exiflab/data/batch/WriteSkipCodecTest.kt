package io.github.fishpimp.exiflab.data.batch

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.write.ExifIfd
import io.github.fishpimp.exiflab.metadata.write.WriteSkip
import org.junit.Test

class WriteSkipCodecTest {
    @Test
    fun `every kind of skip survives a round trip`() {
        val skips = listOf(
            WriteSkip.IptcUnsupported(ImageFormat.Png, 3),
            WriteSkip.HeifColorProfileKept,
            WriteSkip.StructuralTag(ExifIfd.Exif, 0xA005),
            WriteSkip.IptcDatasetRejected(2, 120, tooLong = true),
            WriteSkip.MakerNoteMoved,
        )

        assertThat(WriteSkipCodec.decode(WriteSkipCodec.encode(skips))).containsExactlyElementsIn(skips).inOrder()
    }

    @Test
    fun `the English detail of dropped EXIF data is not stored`() {
        val encoded = WriteSkipCodec.encode(listOf(WriteSkip.ExifDataDropped("IFD1 at offset 1234 points outside the block")))

        assertThat(encoded).doesNotContain("offset")
        assertThat(WriteSkipCodec.decode(encoded)).containsExactly(WriteSkip.ExifDataDropped(""))
    }

    @Test
    fun `no skips store nothing, and unreadable or unknown notes are left out`() {
        assertThat(WriteSkipCodec.encode(emptyList())).isNull()
        assertThat(WriteSkipCodec.decode(null)).isEmpty()
        assertThat(WriteSkipCodec.decode("IPTC\nMakerNote moved")).isEmpty()
        assertThat(WriteSkipCodec.decode("""[{"kind":"somethingNew"},{"kind":"makerNoteMoved"}]"""))
            .containsExactly(WriteSkip.MakerNoteMoved)
    }
}
