package io.github.fishpimp.exiflab.data.save

import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.ImageSource
import io.github.fishpimp.exiflab.metadata.Metadata
import io.github.fishpimp.exiflab.metadata.write.MetadataChanges
import io.github.fishpimp.exiflab.metadata.write.MetadataWriter
import io.github.fishpimp.exiflab.metadata.write.MetadataWriting
import io.github.fishpimp.exiflab.metadata.write.SidecarWriter
import io.github.fishpimp.exiflab.metadata.write.WriteResult
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Thrown when an edited file fails the re-read check before it replaces anything. */
class WrittenFileCheckException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * The parts of the metadata engine the save pipeline uses. The writers are looked up only when
 * needed, so an engine that is not available yet makes in-place saves unavailable instead of
 * failing app start.
 *
 * @param check re-reads an edited file before it replaces anything; throws when it cannot be parsed.
 */
class WriteEngine(
    private val writer: () -> MetadataWriter,
    private val sidecar: () -> SidecarWriter,
    private val check: (source: ImageSource, format: ImageFormat) -> Unit,
) {
    /** True when [format] can be rewritten (never RAW). False too while no writer is available. */
    fun canWrite(format: ImageFormat): Boolean =
        format != ImageFormat.Unknown && !format.isRaw && runCatching { writer().canWrite(format) }.getOrDefault(false)

    fun write(source: ImageSource, changes: MetadataChanges, output: OutputStream): WriteResult =
        writer().write(source, changes, output)

    fun writeSidecar(existing: ByteArray?, changes: MetadataChanges, output: OutputStream) =
        sidecar().write(existing, changes, output)

    /** Re-reads an edited file; throws [WrittenFileCheckException] when it does not parse as [format]. */
    fun verifyReadable(source: ImageSource, format: ImageFormat) {
        try {
            check(source, format)
        } catch (e: WrittenFileCheckException) {
            throw e
        } catch (e: Exception) {
            throw WrittenFileCheckException("The edited file cannot be read back", e)
        }
    }

    companion object {
        /** The app's engine: [MetadataWriting] writers, checked with [Metadata.reader]. */
        fun default() = WriteEngine(
            writer = { MetadataWriting.writer },
            sidecar = { MetadataWriting.sidecar },
            check = { source, format ->
                val report = Metadata.reader.read(source)
                if (format != ImageFormat.Unknown && report.format != format) {
                    throw WrittenFileCheckException("Expected ${format.displayName}, read ${report.format.displayName}")
                }
            },
        )
    }
}

/** An [ImageSource] over a local file, such as a backup or a temp file. */
class FileImageSource(private val file: File, override val fileName: String?) : ImageSource {
    override val length: Long get() = file.length()
    override fun open(): InputStream = file.inputStream()
}

/** An [ImageSource] over a document, read through [DocumentAccess]. */
class DocumentImageSource(
    private val documents: DocumentAccess,
    private val uri: String,
    override val fileName: String?,
    override val length: Long?,
) : ImageSource {
    override fun open(): InputStream = documents.openInput(uri)
}
