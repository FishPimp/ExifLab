package io.github.fishpimp.exiflab.data.shareclean

import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.save.FileImageSource
import io.github.fishpimp.exiflab.metadata.ByteArrayImageSource
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.ImageSource
import io.github.fishpimp.exiflab.metadata.MetadataReader
import io.github.fishpimp.exiflab.metadata.PreviewExtractor
import io.github.fishpimp.exiflab.metadata.UnsupportedImageException
import io.github.fishpimp.exiflab.metadata.edit.ChangePlanner
import io.github.fishpimp.exiflab.metadata.edit.EditOperation
import io.github.fishpimp.exiflab.metadata.edit.StripCategory
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.write.ExifChange
import io.github.fishpimp.exiflab.metadata.write.ExifIfd
import io.github.fishpimp.exiflab.metadata.write.EmbeddedExtras
import io.github.fishpimp.exiflab.metadata.write.ExifValue
import io.github.fishpimp.exiflab.metadata.write.ImageDataMismatchException
import io.github.fishpimp.exiflab.metadata.write.MetadataBlock
import io.github.fishpimp.exiflab.metadata.write.MetadataChanges
import io.github.fishpimp.exiflab.metadata.write.MetadataWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** What became of one photo: a [CleanedFile] or a [Refusal]. */
sealed interface CleanOutcome

/** A cleaned copy, ready to be shared. */
data class CleanedFile(
    val file: File,
    val mimeType: String,
    /** The name of the photo it was made from. */
    val sourceName: String,
    /** True when this is the JPEG preview of a RAW file rather than the file itself. */
    val fromRawPreview: Boolean = false,
) : CleanOutcome

/** Why a photo is not shared. */
sealed interface RefusalReason {
    /** A RAW file, and the user chose not to share RAW previews. */
    data object RawLeftOut : RefusalReason

    /** A RAW file without an embedded preview to share instead. */
    data object NoRawPreview : RefusalReason

    /** A type ExifLab cannot clean, such as TIFF. [formatName] is null when the type is unknown. */
    data class Unsupported(val formatName: String?) : RefusalReason

    /** The photo could not be read. */
    data object Unreadable : RefusalReason

    /** The cleaned copy could not be written (including a write that would have changed the image). */
    data object WriteFailed : RefusalReason

    /** Reading the cleaned copy back still found [kinds] that should have been removed. */
    data class StillContains(val kinds: List<MetadataKind>) : RefusalReason

    /**
     * The file carries data besides its image that cannot be left out of a copy of this type,
     * such as a Motion Photo video box in a HEIC file, see [EmbeddedExtras].
     */
    data object EmbeddedExtras : RefusalReason
}

data class Refusal(val name: String, val reason: RefusalReason) : CleanOutcome

/** Outcome of [ShareCleaner.clean]: the copies to share, in the order given, and the photos left out. */
data class ShareCleanResult(val files: List<CleanedFile>, val refusals: List<Refusal>)

/** Turns photos into cleaned copies; see [ShareCleaner]. */
fun interface ShareCleaning {
    suspend fun clean(
        photos: List<PhotoRef>,
        choice: ShareCleanChoice,
        onProgress: (done: Int, total: Int) -> Unit,
    ): ShareCleanResult
}

/**
 * Makes cleaned copies of photos for sharing. Each photo's strip is planned against its own
 * metadata and written losslessly into the [store] by the metadata writer, which verifies that
 * the image data is byte-identical. The copy is then read back, and refused (deleted, never
 * shared) when anything the chosen categories cover is still found in it.
 *
 * A copy is the primary image only: a Motion Photo's video, vendor trailers and MPF secondary
 * images can hold their own location and dates where the reader does not look, so they are left
 * out ([MetadataBlock.EmbeddedMedia]), and a photo whose extras cannot be left out is refused.
 * When dates or device details are removed, the copy is also renamed ("photo-1.jpg"), because
 * camera file names such as PXL_20260612_174207123.jpg carry both.
 *
 * RAW files are never rewritten: with [ShareCleanChoice.includeRawPreviews] their embedded JPEG
 * preview is shared instead, stripped of everything but its orientation.
 *
 * Nothing about the photos' content is logged.
 */
class ShareCleaner(
    private val open: (PhotoRef) -> ImageSource,
    private val reader: () -> MetadataReader,
    private val planner: () -> ChangePlanner,
    private val writer: () -> MetadataWriter,
    private val previews: () -> PreviewExtractor,
    private val store: SharedFilesStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ShareCleaning {

    override suspend fun clean(
        photos: List<PhotoRef>,
        choice: ShareCleanChoice,
        onProgress: (done: Int, total: Int) -> Unit,
    ): ShareCleanResult = withContext(ioDispatcher) {
        require(choice.canShare) { "Nothing to remove" }
        store.deleteOlderThan()
        val files = mutableListOf<CleanedFile>()
        val refusals = mutableListOf<Refusal>()
        val created = mutableListOf<File>()
        try {
            photos.forEachIndexed { index, ref ->
                ensureActive()
                val directory = store.newDirectory().also(created::add)
                when (val outcome = cleanOne(ref, index, choice, directory)) {
                    is CleanedFile -> files += outcome
                    is Refusal -> {
                        directory.deleteRecursively()
                        refusals += outcome
                    }
                }
                onProgress(index + 1, photos.size)
            }
        } catch (e: Throwable) {
            // Cancelled, or out of room: nothing of this share is handed on, so nothing is kept.
            created.forEach { it.deleteRecursively() }
            throw e
        }
        ShareCleanResult(files, refusals)
    }

    /** A [CleanedFile] in [directory] for the [index]th photo of the share, or why there is none. */
    private fun cleanOne(ref: PhotoRef, index: Int, choice: ShareCleanChoice, directory: File): CleanOutcome {
        val name = ref.displayName?.takeIf { it.isNotBlank() } ?: UNTITLED
        fun refuse(reason: RefusalReason) = Refusal(name, reason)
        val neutralName = neutralName(index)

        val source = open(ref)
        val report = try {
            reader().read(source)
        } catch (e: CancellationException) {
            throw e
        } catch (_: UnsupportedImageException) {
            return refuse(RefusalReason.Unsupported(null))
        } catch (_: Exception) {
            return refuse(RefusalReason.Unreadable)
        }
        val format = report.format
        return when {
            format.isRaw -> if (choice.includeRawPreviews) cleanRawPreview(name, neutralName, source, report, directory) else refuse(RefusalReason.RawLeftOut)
            format == ImageFormat.Unknown || !runCatching { writer().canWrite(format) }.getOrDefault(false) ->
                refuse(RefusalReason.Unsupported(format.displayName.takeUnless { format == ImageFormat.Unknown }))
            else -> {
                val categories = choice.categories
                val renamed = categories.any { it in NAME_REVEALS }
                val target = File(directory, fileNameFor(if (renamed) neutralName else name, format, keepExtension = !renamed))
                writeAndVerify(name, source, report, categories, MetadataChanges(), target, fromRawPreview = false)
            }
        }
    }

    /**
     * The JPEG preview inside a RAW file, with everything but its orientation removed, named
     * [copyName] since everything goes.
     */
    private fun cleanRawPreview(name: String, copyName: String, raw: ImageSource, rawReport: MetadataReport, directory: File): CleanOutcome {
        val preview = try {
            previews().extract(raw)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return Refusal(name, RefusalReason.NoRawPreview)
        val target = File(directory, fileNameFor(copyName, ImageFormat.Jpeg, keepExtension = false))
        val source = ByteArrayImageSource(preview.jpegBytes, target.name)
        val report = try {
            reader().read(source)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Refusal(name, RefusalReason.NoRawPreview)
        }
        if (report.format != ImageFormat.Jpeg) return Refusal(name, RefusalReason.NoRawPreview)
        // Keep the preview upright: the RAW file's orientation usually applies to it too.
        val orientation = (preview.orientation ?: rawReport.summary.orientation)?.takeIf { it in 2..8 }
        val upright = orientation?.let {
            MetadataChanges(exif = listOf(ExifChange.Set(ExifIfd.Primary, ORIENTATION_TAG, ExifValue.Shorts(listOf(it)))))
        } ?: MetadataChanges()
        return writeAndVerify(name, source, report, setOf(StripCategory.All), upright, target, fromRawPreview = true)
    }

    private fun writeAndVerify(
        name: String,
        source: ImageSource,
        report: MetadataReport,
        categories: Set<StripCategory>,
        extra: MetadataChanges,
        target: File,
        fromRawPreview: Boolean,
    ): CleanOutcome {
        // Data past the primary image goes; a type that cannot leave it out is not shared.
        val extras = try {
            EmbeddedExtras.find(source)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Refusal(name, RefusalReason.Unreadable)
        }
        if (!EmbeddedExtras.canDrop(report.format, extras)) return Refusal(name, RefusalReason.EmbeddedExtras)
        val dropExtras = if (extras.isEmpty()) MetadataChanges() else MetadataChanges(removeBlocks = setOf(MetadataBlock.EmbeddedMedia))
        try {
            val changes = planner().plan(report, listOf(EditOperation.Strip(categories))).changes + extra + dropExtras
            if (changes.isEmpty) {
                // Nothing the categories cover is in the file: the copy is the file itself.
                source.open().use { input -> target.outputStream().use { input.copyTo(it) } }
            } else {
                val result = target.outputStream().buffered().use { writer().write(source, changes, it) }
                if (result.sourceImageDigest != result.outputImageDigest) throw ImageDataMismatchException("Image data changed")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            target.delete()
            return Refusal(name, RefusalReason.WriteFailed)
        }

        val cleaned = try {
            reader().read(FileImageSource(target, target.name))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (cleaned == null || cleaned.format != report.format) {
            target.delete()
            return Refusal(name, RefusalReason.WriteFailed)
        }
        val extrasLeft = runCatching { EmbeddedExtras.find(FileImageSource(target, target.name)) }.getOrNull()
        if (extrasLeft == null || extrasLeft.isNotEmpty()) {
            target.delete()
            return Refusal(name, if (extrasLeft == null) RefusalReason.WriteFailed else RefusalReason.EmbeddedExtras)
        }
        val leftovers = MetadataInventory.of(cleaned, categories)
        if (!leftovers.isEmpty) {
            target.delete()
            return Refusal(name, RefusalReason.StillContains(leftovers.kinds))
        }
        return CleanedFile(target, report.format.mimeType, sourceName = name, fromRawPreview = fromRawPreview)
    }

    internal companion object {
        const val UNTITLED = "photo"
        const val ORIENTATION_TAG = 0x0112
        private const val MAX_NAME_LENGTH = 120

        /** Categories whose removal the original file name would undo (capture time, phone or camera brand). */
        private val NAME_REVEALS = setOf(StripCategory.All, StripCategory.Dates, StripCategory.DeviceInfo)

        /** The name of the [index]th copy when the original name must not be shared. */
        fun neutralName(index: Int) = "$UNTITLED-${index + 1}"

        /**
         * A safe file name for the copy of [name]: no path separators or control characters, not
         * too long, with an extension for [format] (the original one when [keepExtension] and it
         * matches the format, so "IMG_1.JPG" stays "IMG_1.JPG").
         */
        fun fileNameFor(name: String, format: ImageFormat, keepExtension: Boolean): String {
            val cleaned = name.map { if (it == '/' || it == '\\' || it.isISOControl() || it == ':') '_' else it }.joinToString("").trim().trimStart('.')
            val dot = cleaned.lastIndexOf('.')
            val base = (if (dot > 0) cleaned.substring(0, dot) else cleaned).ifBlank { UNTITLED }.take(MAX_NAME_LENGTH)
            val extension = if (dot > 0) cleaned.substring(dot + 1) else ""
            val keep = keepExtension && extension.lowercase() in format.extensions
            return "$base.${if (keep) extension else format.extensions.firstOrNull() ?: "bin"}"
        }
    }
}
