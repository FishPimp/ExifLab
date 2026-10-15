package io.github.fishpimp.exiflab.data.batch

import io.github.fishpimp.exiflab.data.history.EditMode
import io.github.fishpimp.exiflab.data.history.EditRecord
import io.github.fishpimp.exiflab.data.history.EditState
import io.github.fishpimp.exiflab.data.history.SaveError
import io.github.fishpimp.exiflab.data.photos.PhotoFormats
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.photos.format
import io.github.fishpimp.exiflab.data.save.SaveOutcome
import io.github.fishpimp.exiflab.data.save.SaveTarget
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.edit.ChangePlanner
import io.github.fishpimp.exiflab.metadata.edit.DateAction
import io.github.fishpimp.exiflab.metadata.edit.EditOperation
import io.github.fishpimp.exiflab.metadata.edit.EditPlan
import io.github.fishpimp.exiflab.metadata.edit.FieldDiff
import io.github.fishpimp.exiflab.metadata.edit.PlanWarning
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.LocationStatus
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.PhotoSummary
import io.github.fishpimp.exiflab.metadata.write.MetadataBlock
import io.github.fishpimp.exiflab.metadata.write.MetadataChanges
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay

/** Photos for batch tests; the file name decides how the fakes treat them. */
internal object BatchPhotos {
    fun folder(name: String, writable: Boolean = true) = PhotoRef(
        uri = "content://fake/tree/t/document/$name",
        displayName = name,
        mimeType = PhotoFormats.mimeTypeFromName(name),
        size = 1_000,
        lastModified = 1,
        origin = PhotoOrigin.Folder,
        writable = writable,
        parentDocumentUri = "content://fake/tree/t/document/folder",
    )

    fun picker(name: String) = PhotoRef(
        uri = "content://media/picker/$name",
        displayName = name,
        mimeType = PhotoFormats.mimeTypeFromName(name),
        size = 1_000,
        lastModified = 1,
        origin = PhotoOrigin.Picker,
        writable = false,
    )

    fun report(ref: PhotoRef) = MetadataReport(
        format = ref.format,
        fileName = ref.displayName,
        fileSize = ref.size,
        summary = PhotoSummary(),
        location = null,
        locationStatus = LocationStatus.Absent,
        directories = emptyList(),
        sensitiveFindings = emptyList(),
        warnings = emptyList(),
    )
}

/** Plans one change for every photo, except photos named "same*" (nothing to change, with a warning). */
internal class BatchFakePlanner : ChangePlanner {
    override fun plan(report: MetadataReport, operations: List<EditOperation>): EditPlan {
        if (report.fileName.orEmpty().startsWith("same")) return EditPlan(MetadataChanges(), emptyList(), listOf(PlanWarning.NoCaptureDate(DateAction.Shift)))
        return EditPlan(
            changes = MetadataChanges(removeBlocks = setOf(MetadataBlock.Xmp)),
            diff = listOf(FieldDiff("Artist", DirectoryGroup.Exif, null, "Maja")),
        )
    }
}

/**
 * The save pipeline as the runner sees it. Writes nothing; photos named "fail*" fail after
 * journaling. Tracks how many saves run at once.
 */
internal class FakeBatchSaving(
    private val writable: Set<ImageFormat> = setOf(ImageFormat.Jpeg, ImageFormat.Png, ImageFormat.WebP),
    private val saveDelayMs: Long = 0,
    private val onSave: suspend (PhotoRef) -> Unit = {},
) : BatchSaving {
    val saves = mutableListOf<Pair<PhotoRef, SaveTarget>>()
    val records = HashMap<String, EditRecord>()
    var batchCreated = 0
    var recoveries = 0
    var failedCount = 0
    var cancelledCount = 0
    private val active = AtomicInteger()
    val maxActive = AtomicInteger()

    override fun canWrite(format: ImageFormat) = format in writable

    override suspend fun startBatch(title: String, total: Int): String {
        batchCreated++
        return BATCH_ID
    }

    override suspend fun save(ref: PhotoRef, plan: EditPlan, target: SaveTarget, batchId: String): SaveOutcome {
        val now = active.incrementAndGet()
        maxActive.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        try {
            onSave(ref)
            if (saveDelayMs > 0) delay(saveDelayMs)
            synchronized(saves) { saves += ref to target }
            val (mode, destination) = when (target) {
                SaveTarget.InPlace -> EditMode.InPlace to ref.uri
                is SaveTarget.Copy -> EditMode.Copy to target.destinationUri
                is SaveTarget.Sidecar -> {
                    val destination = target.destinationUri
                        ?: ref.parentDocumentUri?.let { "${ref.uri}.xmp" }
                        ?: return SaveOutcome.NeedsSidecarDestination("${ref.displayName}.xmp")
                    EditMode.Sidecar to destination
                }
            }
            val failed = ref.displayName.orEmpty().startsWith("fail")
            val record = record(ref, mode, if (failed) EditState.Failed else EditState.Committed, target = destination.takeIf { mode != EditMode.InPlace })
            synchronized(records) { records[ref.uri] = record }
            if (failed) {
                failedCount++
                return SaveOutcome.Failed(record.id, SaveError.WriteFailed)
            }
            return SaveOutcome.Saved(record.id, mode, destination)
        } finally {
            active.decrementAndGet()
        }
    }

    override suspend fun recoverInterrupted() {
        recoveries++
    }

    override suspend fun latestRecord(batchId: String, photoUri: String): EditRecord? = synchronized(records) { records[photoUri] }

    override suspend fun countFailed(batchId: String) {
        failedCount++
    }

    override suspend fun uncountFailed(batchId: String) {
        failedCount--
    }

    override suspend fun countCancelled(batchId: String, count: Int) {
        cancelledCount += count
    }

    companion object {
        const val BATCH_ID = "edit-batch"

        fun record(ref: PhotoRef, mode: EditMode, state: EditState, target: String? = null, error: SaveError? = null) = EditRecord(
            id = "record-${ref.displayName}",
            batchId = BATCH_ID,
            photoUri = ref.uri,
            displayName = ref.displayName,
            mimeType = ref.mimeType,
            photoOrigin = ref.origin,
            parentDocumentUri = ref.parentDocumentUri,
            mode = mode,
            targetUri = target,
            targetName = null,
            state = state,
            backupPath = null,
            originalSize = 1,
            originalSha256 = "a",
            newSha256 = "b",
            diffJson = "[]",
            summary = "Artist",
            createdAt = 1,
            completedAt = 2,
            error = error?.name,
            restoresRecordId = null,
        )
    }
}

/** A folder for copies that remembers what was created and deleted. */
internal class FakeDestinations : BatchDestinations {
    val created = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    private val names = mutableSetOf("IMG_0001.jpg")

    override fun createCopy(folderUri: String, name: String, mimeType: String): String = synchronized(this) {
        val unique = CopyNames.unique(name) { it in names }
        names += unique
        "$folderUri/$unique".also { created += it }
    }

    override fun sidecarIn(folderUri: String, name: String): String = "$folderUri/$name".also { synchronized(this) { created += it } }

    override fun deleteIfEmpty(uri: String) {
        synchronized(this) { deleted += uri }
    }
}
