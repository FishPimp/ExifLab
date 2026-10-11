package io.github.fishpimp.exiflab.data.batch

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.edit.EditOperation
import io.github.fishpimp.exiflab.metadata.edit.StripCategory
import org.junit.Test
import java.time.Duration
import java.time.ZoneId

/** Routing per file kind, copy names and the stored operation format. Pure JVM. */
class BatchRoutingTest {
    private val writers: (ImageFormat) -> Boolean = { it == ImageFormat.Jpeg || it == ImageFormat.Png || it == ImageFormat.WebP }

    private fun route(ref: io.github.fishpimp.exiflab.data.photos.PhotoRef, kind: BatchKind = BatchKind.Location, folder: Boolean = false) =
        BatchRouting.route(ref, null, kind, writers, folder)

    @Test
    fun `writable photos are edited in place and RAW gets a sidecar`() {
        assertThat(route(BatchPhotos.folder("a.jpg"))).isEqualTo(RouteDecision.InPlace)
        assertThat(route(BatchPhotos.folder("a.webp"))).isEqualTo(RouteDecision.InPlace)
        assertThat(route(BatchPhotos.folder("a.NEF", writable = false))).isEqualTo(RouteDecision.Sidecar)
        assertThat(route(BatchPhotos.folder("a.dng"))).isEqualTo(RouteDecision.Sidecar)
        assertThat(route(BatchPhotos.folder("a.heic"))).isEqualTo(RouteDecision.Sidecar)
        assertThat(BatchRouting.route(BatchPhotos.folder("a.heic"), null, BatchKind.Location, { true }, false)).isEqualTo(RouteDecision.InPlace)
    }

    @Test
    fun `read-only photos need the folder for copies`() {
        val picked = BatchPhotos.picker("p.jpg")
        assertThat(route(picked)).isEqualTo(RouteDecision.skip(BatchItemReason.ReadOnly))
        assertThat(route(picked, folder = true)).isEqualTo(RouteDecision.Copy)
        assertThat(route(BatchPhotos.folder("r.jpg", writable = false), folder = true)).isEqualTo(RouteDecision.Copy)
        assertThat(route(BatchPhotos.picker("p.CR3"))).isEqualTo(RouteDecision.skip(BatchItemReason.NoSidecarPlace))
        assertThat(BatchRouting.needsFolder(picked, null, BatchKind.Location, writers)).isTrue()
        assertThat(BatchRouting.needsFolder(BatchPhotos.picker("p.CR3"), null, BatchKind.Location, writers)).isTrue()
        assertThat(BatchRouting.needsFolder(BatchPhotos.folder("a.CR3"), null, BatchKind.Location, writers)).isFalse()
        assertThat(BatchRouting.needsFolder(BatchPhotos.folder("a.jpg"), null, BatchKind.Location, writers)).isFalse()
    }

    @Test
    fun `stripping never targets sidecars`() {
        assertThat(route(BatchPhotos.folder("a.ARW"), BatchKind.Strip)).isEqualTo(RouteDecision.skip(BatchItemReason.CannotStrip))
        assertThat(route(BatchPhotos.folder("a.jpg"), BatchKind.Strip)).isEqualTo(RouteDecision.InPlace)
    }

    @Test
    fun `the sniffed format decides for files without a known extension`() {
        val noExtension = BatchPhotos.folder("photo").copy(mimeType = null)
        assertThat(BatchRouting.route(noExtension, ImageFormat.Jpeg, BatchKind.Author, writers, false)).isEqualTo(RouteDecision.InPlace)
        assertThat(BatchRouting.route(noExtension, ImageFormat.Raf, BatchKind.Author, writers, false)).isEqualTo(RouteDecision.Sidecar)
    }

    @Test
    fun `copy names never replace an existing file`() {
        val taken = setOf("IMG_1.jpg", "IMG_1 (1).jpg", "notes")
        assertThat(CopyNames.unique("IMG_2.jpg") { it in taken }).isEqualTo("IMG_2.jpg")
        assertThat(CopyNames.unique("IMG_1.jpg") { it in taken }).isEqualTo("IMG_1 (2).jpg")
        assertThat(CopyNames.unique("notes") { it in taken }).isEqualTo("notes (1)")
        assertThat(CopyNames.unique(".hidden") { it == ".hidden" }).isEqualTo(".hidden (1)")
    }

    @Test
    fun `operations survive storage and map to planner operations`() {
        val operations = listOf(
            BatchOperation.SetLocation(59.3293, 18.0686, 12.5),
            BatchOperation.ShiftTime(-3_600),
            BatchOperation.FixTimeZone("Europe/Stockholm", "Asia/Tokyo"),
            BatchOperation.SetAuthorship("Maja", null, changeCopyright = false),
            BatchOperation.Strip.of(setOf(StripCategory.DeviceIdentifiers, StripCategory.Location)),
        )
        operations.forEach { assertThat(BatchOperation.decodeOrNull(BatchOperation.encode(it))).isEqualTo(it) }
        assertThat(BatchOperation.decodeOrNull("{\"type\":\"unknown\"}")).isNull()

        assertThat(operations[1].toEditOperation()).isEqualTo(EditOperation.ShiftCaptureTime(Duration.ofHours(-1)))
        assertThat(operations[2].toEditOperation())
            .isEqualTo(EditOperation.ChangeTimeZone(ZoneId.of("Europe/Stockholm"), ZoneId.of("Asia/Tokyo")))
        assertThat(operations[4].toEditOperation())
            .isEqualTo(EditOperation.Strip(setOf(StripCategory.Location, StripCategory.DeviceIdentifiers)))
        assertThat(operations.map { it.kind }).containsExactlyElementsIn(BatchKind.entries).inOrder()
    }
}
