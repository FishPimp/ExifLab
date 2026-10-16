package io.github.fishpimp.exiflab.metadata.write

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.ByteArrayImageSource
import io.github.fishpimp.exiflab.metadata.fixtures.Containers
import io.github.fishpimp.exiflab.metadata.fixtures.Photos
import io.github.fishpimp.exiflab.metadata.fixtures.Photos.tag
import io.github.fishpimp.exiflab.metadata.fixtures.Photos.tagOrNull
import io.github.fishpimp.exiflab.metadata.fixtures.TiffBuilder
import io.github.fishpimp.exiflab.metadata.fixtures.Xmp
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.ascii
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.changes
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.write
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.random.Random
import org.junit.Test

/** Motion Photo video, vendor trailers and MPF images: found, and left out of a copy on request. */
class EmbeddedExtrasTest {
    private fun extras(bytes: ByteArray, name: String = "photo.jpg") = EmbeddedExtras.find(ByteArrayImageSource(bytes, name))

    /** Writes [changes] plus [MetadataBlock.EmbeddedMedia]; the digests match over the primary image. */
    private fun copy(bytes: ByteArray, changes: MetadataChanges = MetadataChanges(), name: String = "photo.jpg"): ByteArray {
        val out = ByteArrayOutputStream()
        val result = MetadataWriting.writer.write(
            ByteArrayImageSource(bytes, name),
            changes.copy(removeBlocks = changes.removeBlocks + MetadataBlock.EmbeddedMedia),
            out,
        )
        assertThat(result.outputImageDigest).isEqualTo(result.sourceImageDigest)
        assertThat(result.bytesWritten).isEqualTo(out.size().toLong())
        return out.toByteArray()
    }

    private val motionXmp = Containers.xmpSegment(
        Xmp.packet(
            "<rdf:Description rdf:about=\"\" xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" " +
                "xmlns:Container=\"http://ns.google.com/photos/1.0/container/\" xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\" " +
                "xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\" xmp:Rating=\"4\">" +
                "<Container:Directory><rdf:Seq><rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\"/></rdf:li>" +
                "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\"6000\"/></rdf:li>" +
                "</rdf:Seq></Container:Directory></rdf:Description>",
        ),
    )

    /** An MP4-like tail: an ftyp box and a location atom, as a phone appends after EOI. */
    private val video = byteArrayOf(0, 0, 0, 0x18) + "ftypmp42".toByteArray() + ByteArray(12) + "©xyz+59.3293+018.0686/".toByteArray(Charsets.ISO_8859_1) +
        Random(3).nextBytes(6000)

    private fun motionPhoto(primary: ByteArray = Photos.jpeg(TiffBuilder().build(Photos.ifd0(Photos.exifIfd(), Photos.gpsIfd())), motionXmp)) =
        primary + video

    private fun progressiveJpeg(): ByteArray {
        val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 64) for (y in 0 until 48) image.setRGB(x, y, (x * 4 shl 16) or (y * 5 shl 8) or 90)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam.apply { progressiveMode = ImageWriteParam.MODE_DEFAULT }
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    @Test fun findsVideoAfterTheImageAndMpfImages() {
        assertThat(extras(Photos.jpeg(null))).isEmpty()
        assertThat(extras(motionPhoto())).containsExactly(EmbeddedExtra.TrailingData)
        // Padding after EOI is not data.
        assertThat(extras(Photos.jpeg(null) + ByteArray(512))).isEmpty()
        // A progressive image has marker segments between its scans; none of them ends the image.
        val progressive = progressiveJpeg()
        assertThat(extras(progressive)).isEmpty()
        assertThat(extras(progressive + video)).containsExactly(EmbeddedExtra.TrailingData)
        val png = Containers.png()
        assertThat(extras(png, "a.png")).isEmpty()
        assertThat(extras(png + video, "a.png")).containsExactly(EmbeddedExtra.TrailingData)
        val mpo = Containers.jpegWithSegments(Photos.jpeg(null), 0xE2 to ("MPF\u0000".toByteArray() + TiffBuilder.tiff { long(0xB001, 2) })) + Containers.jpeg(8, 8)
        assertThat(extras(mpo)).containsExactly(EmbeddedExtra.SecondaryImages, EmbeddedExtra.TrailingData)
    }

    @Test fun aCopyWithoutEmbeddedMediaEndsAtTheImageAndDropsWhatPointsPastIt() {
        val source = motionPhoto()
        val bytes = copy(source, changes(ascii(ExifIfd.Primary, Photos.ARTIST, "Someone")))
        assertThat(extras(bytes)).isEmpty()
        assertThat(bytes.copyOfRange(bytes.size - 2, bytes.size)).isEqualTo(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        // The image data is the primary image's, unchanged.
        assertThat(WriteFixtures.digest(bytes)).isEqualTo(WriteFixtures.digest(Photos.jpeg(null)))
        val after = Photos.read(bytes)
        assertThat(after.tag("exif-ifd0", "Artist").rawValue).isEqualTo("Someone")
        assertThat(after.tag("xmp", "xmp:Rating").rawValue).isEqualTo("4")
        assertThat(after.tagOrNull("xmp", "GCamera:MotionPhoto")).isNull()
        assertThat(after.directories.flatMap { it.tags }.map { it.name }.filter { it.startsWith("Container:") }).isEmpty()
        // Only the metadata changes otherwise: GPS is still there, as nothing asked to remove it.
        assertThat(after.locationStatus).isEqualTo(Photos.read(source).locationStatus)

        // The same for a progressive image, with the block removal alone.
        val progressive = progressiveJpeg()
        assertThat(copy(progressive + video)).isEqualTo(progressive)
        // Without the block the video stays, byte for byte.
        val kept = write(source, changes(ascii(ExifIfd.Primary, Photos.ARTIST, "Someone"))).bytes
        assertThat(kept.copyOfRange(kept.size - video.size, kept.size)).isEqualTo(video)
    }

    @Test fun mpfImagesAndTheirIndexAreLeftOut() {
        val secondary = Containers.jpeg(8, 8)
        val primary = Containers.jpegWithSegments(Photos.jpeg(null), 0xE2 to ("MPF\u0000".toByteArray() + TiffBuilder.tiff { long(0xB001, 2) }))
        val written = copy(primary + secondary)
        assertThat(JpegStructure.segments(written).map { it.label }).doesNotContain("MPF")
        assertThat(written).isEqualTo(Photos.jpeg(null))
    }

    @Test fun pngBytesAfterIendAreLeftOut() {
        val png = Containers.png()
        assertThat(copy(png + video, name = "a.png")).isEqualTo(png)
        assertThat(EmbeddedExtras.canDrop(io.github.fishpimp.exiflab.metadata.ImageFormat.Png, setOf(EmbeddedExtra.TrailingData))).isTrue()
        assertThat(EmbeddedExtras.canDrop(io.github.fishpimp.exiflab.metadata.ImageFormat.Heif, setOf(EmbeddedExtra.ExtraBoxes))).isFalse()
    }
}
