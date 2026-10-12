package io.github.fishpimp.exiflab.ui.batch

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.batch.BatchFakePlanner
import io.github.fishpimp.exiflab.data.batch.BatchItemReason
import io.github.fishpimp.exiflab.data.batch.BatchKind
import io.github.fishpimp.exiflab.data.batch.BatchOperation
import io.github.fishpimp.exiflab.data.batch.BatchPhotos
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.edit.StripCategory
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The batch preview's counts, warnings and example. Pure JVM. */
class BatchAnalyzerTest {
    private val photos = listOf(
        BatchPhotos.folder("a.jpg"),
        BatchPhotos.folder("same1.jpg"),
        BatchPhotos.folder("b.NEF", writable = false),
        BatchPhotos.picker("p.jpg"),
        BatchPhotos.picker("q.CR3"),
        BatchPhotos.folder("same2.png"),
        BatchPhotos.folder("broken.jpg"),
    )

    private fun analyzer() = BatchAnalyzer(
        readReport = { ref -> if (ref.displayName == "broken.jpg") error("unreadable") else BatchPhotos.report(ref) },
        planner = { BatchFakePlanner() },
        canWrite = { it == ImageFormat.Jpeg || it == ImageFormat.Png },
    )

    private val operation = BatchOperation.ShiftTime(3_600)

    @Test
    fun `file types give the routes before anything is read`() {
        val initial = analyzer().initial(photos, BatchKind.ShiftTime)
        assertThat(initial.checked).isEqualTo(0)
        assertThat(initial.inPlace).isEqualTo(4)
        assertThat(initial.sidecars).isEqualTo(1)
        assertThat(initial.copiesNeedingFolder).isEqualTo(1)
        assertThat(initial.sidecarsNeedingFolder).isEqualTo(1)
        assertThat(initial.needsFolder).isEqualTo(2)
    }

    @Test
    fun `checking every photo counts unchanged and unreadable ones and groups warnings`() = runTest {
        val previews = analyzer().analyze(photos, operation).toList()
        val final = previews.last()

        assertThat(previews.first().checked).isEqualTo(0)
        assertThat(final.isComplete).isTrue()
        assertThat(final.unchanged).isEqualTo(2)
        assertThat(final.unreadable).isEqualTo(1)
        assertThat(final.inPlace).isEqualTo(1)
        assertThat(final.warnings).containsExactly(WarningCount(WarningCount.Kind.NoCaptureDate, 2))
        assertThat(final.sample?.ref?.displayName).isEqualTo("a.jpg")

        // Without a folder, the read-only photo and the picker RAW are skipped too.
        assertThat(final.written(hasFolder = false)).isEqualTo(2)
        assertThat(final.skipped(hasFolder = false)).isEqualTo(4)
        assertThat(final.written(hasFolder = true)).isEqualTo(4)
        assertThat(final.copies(hasFolder = true)).isEqualTo(1)
        assertThat(final.sidecars(hasFolder = true)).isEqualTo(2)
    }

    @Test
    fun `stripping skips RAW with a reason`() = runTest {
        val final = analyzer().analyze(photos, BatchOperation.Strip.of(setOf(StripCategory.Location))).last()
        assertThat(final.skipReasons).containsExactly(BatchItemReason.CannotStrip, 2)
        assertThat(final.sidecars(hasFolder = true)).isEqualTo(0)
    }
}
