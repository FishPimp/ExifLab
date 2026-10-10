package io.github.fishpimp.exiflab.metadata.write

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.ByteArrayImageSource
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.MetadataTag
import io.github.fishpimp.exiflab.metadata.model.Rational
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Shared helpers for the write engine tests. */
object WriteFixtures {
    class Written(val bytes: ByteArray, val result: WriteResult)

    /** Set EXIFLAB_WRITE_TEST_OUT to keep every file the tests write, e.g. to check them with exiftool -validate. */
    private val dumpDir = System.getenv("EXIFLAB_WRITE_TEST_OUT")?.takeIf { it.isNotBlank() }?.let(::File)?.also { it.mkdirs() }
    private val dumped = AtomicInteger()

    /** Writes [changes] into [bytes] and checks the invariants every successful write must hold. */
    fun write(bytes: ByteArray, changes: MetadataChanges, name: String? = null): Written {
        val out = ByteArrayOutputStream()
        val result = MetadataWriting.writer.write(ByteArrayImageSource(bytes, name), changes, out)
        val written = out.toByteArray()
        assertThat(result.bytesWritten).isEqualTo(written.size.toLong())
        assertThat(result.outputImageDigest).isEqualTo(result.sourceImageDigest)
        assertThat(digest(written)).isEqualTo(digest(bytes))
        dumpDir?.let { dir ->
            val test = Thread.currentThread().stackTrace.firstOrNull { it.className.endsWith("Test") }?.methodName ?: "write"
            File(dir, "%03d-%s.%s".format(dumped.incrementAndGet(), test, result.format.extensions.first())).writeBytes(written)
        }
        return Written(written, result)
    }

    fun digest(bytes: ByteArray): String = MetadataWriting.digest.digest(ByteArrayImageSource(bytes))

    fun set(ifd: ExifIfd, tag: Int, value: ExifValue) = ExifChange.Set(ifd, tag, value)

    fun ascii(ifd: ExifIfd, tag: Int, text: String) = ExifChange.Set(ifd, tag, ExifValue.Ascii(text))

    fun rationals(vararg values: Pair<Long, Long>) = ExifValue.Rationals(values.map { Rational(it.first, it.second) })

    fun changes(vararg exif: ExifChange) = MetadataChanges(exif = exif.toList())

    /** Every tag as key to raw value, ignoring tags whose values legitimately change with layout or file size. */
    fun MetadataReport.tagValues(): Map<String, String> = directories
        .filterNot { it.id == "file" }
        .flatMap { it.tags }
        .filterNot { it.isLayoutDependent() }
        .associate { it.key to it.rawValue }

    private fun MetadataTag.isLayoutDependent(): Boolean =
        directoryId.startsWith("exif-thumbnail") && id in setOf(0x0201, 0x0111) || name == "Thumbnail Offset"

    /**
     * Asserts that every tag of [before] is unchanged in [after] except the keys in [changed]
     * (exact keys, or directory id prefixes ending in ':'), and that no tag appeared outside them.
     */
    fun assertUntouched(before: MetadataReport, after: MetadataReport, changed: Set<String> = emptySet()) {
        fun isChanged(key: String) = changed.any { if (it.endsWith(":")) key.startsWith(it) else key == it }
        val old = before.tagValues().filterKeys { !isChanged(it) }
        val new = after.tagValues().filterKeys { !isChanged(it) }
        assertThat(new).containsExactlyEntriesIn(old)
    }
}
