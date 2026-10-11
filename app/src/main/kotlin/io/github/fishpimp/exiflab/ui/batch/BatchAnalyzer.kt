package io.github.fishpimp.exiflab.ui.batch

import androidx.compose.runtime.Immutable
import io.github.fishpimp.exiflab.data.batch.BatchItemReason
import io.github.fishpimp.exiflab.data.batch.BatchKind
import io.github.fishpimp.exiflab.data.batch.BatchOperation
import io.github.fishpimp.exiflab.data.batch.BatchRoute
import io.github.fishpimp.exiflab.data.batch.BatchRouting
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.edit.ChangePlanner
import io.github.fishpimp.exiflab.metadata.edit.FieldDiff
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How the preview expects one photo to end up. */
enum class PreviewOutcome {
    InPlace,
    Sidecar,

    /** A sidecar for a photo outside a granted folder: needs the chosen folder, else skipped. */
    SidecarNeedsFolder,

    /** A read-only photo: a copy in the chosen folder, else skipped. */
    CopyNeedsFolder,

    /** Skipped for a reason that does not depend on the folder (e.g. RAW when stripping). */
    Skipped,

    /** The operation changes nothing in this photo. */
    Unchanged,

    /** The photo could not be read (it would fail). */
    Unreadable,
}

/** A planner warning shared by [count] photos. [text] is the planner's (English) text unless the kind is known. */
@Immutable
data class WarningCount(val kind: Kind, val count: Int, val text: String? = null) {
    enum class Kind { NoCaptureDate, Other }
}

/** The first photo's planned changes, as an example of what every photo gets. */
@Immutable
data class SamplePlan(val ref: PhotoRef, val diff: List<FieldDiff>)

/**
 * What a batch will do, before it runs: how each kind of photo is saved, and what checking every
 * photo's metadata found. Routing counts are known at once from the files; [checked] grows as
 * the photos are read and planned, refining them (photos with nothing to change, unreadable ones).
 */
@Immutable
data class BatchPreview(
    val total: Int,
    val checked: Int,
    val inPlace: Int,
    val sidecars: Int,
    val sidecarsNeedingFolder: Int,
    val copiesNeedingFolder: Int,
    val skipped: Int,
    val unchanged: Int,
    val unreadable: Int,
    val skipReasons: Map<BatchItemReason, Int> = emptyMap(),
    val warnings: List<WarningCount> = emptyList(),
    val sample: SamplePlan? = null,
) {
    val isComplete: Boolean get() = checked >= total

    /** Photos that need the folder for copies (read-only photos, and sidecars of photos outside a folder). */
    val needsFolder: Int get() = sidecarsNeedingFolder + copiesNeedingFolder

    fun copies(hasFolder: Boolean): Int = if (hasFolder) copiesNeedingFolder else 0

    fun sidecars(hasFolder: Boolean): Int = sidecars + if (hasFolder) sidecarsNeedingFolder else 0

    /** Photos that will not be written: skipped, unchanged and, without a folder, those needing it. */
    fun skipped(hasFolder: Boolean): Int = skipped + unchanged + if (hasFolder) 0 else needsFolder

    /** Photos that will be written. */
    fun written(hasFolder: Boolean): Int = inPlace + sidecars(hasFolder) + copies(hasFolder)
}

/**
 * Builds the [BatchPreview] for a selection: routes every photo from its file type, then reads
 * and plans each one ([concurrency] at a time), aggregating planner warnings. Nothing is written
 * and only counts are kept, so it stays light for thousands of photos.
 */
class BatchAnalyzer(
    private val readReport: suspend (PhotoRef) -> MetadataReport,
    private val planner: () -> ChangePlanner,
    private val canWrite: (ImageFormat) -> Boolean,
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The preview from file types alone, before any photo is read. */
    fun initial(photos: List<PhotoRef>, kind: BatchKind): BatchPreview =
        summarize(Array(photos.size) { routeOf(photos[it], null, kind) }, emptyArray(), checked = 0, warnings = emptyMap(), sample = null)

    /** Reads and plans every photo, emitting refined previews (throttled) and a final complete one. */
    fun analyze(photos: List<PhotoRef>, operation: BatchOperation): Flow<BatchPreview> = channelFlow {
        val kind = operation.kind
        val outcomes = Array(photos.size) { routeOf(photos[it], null, kind) }
        val reasons = arrayOfNulls<BatchItemReason>(photos.size)
        photos.forEachIndexed { index, ref -> reasons[index] = skipReason(ref, null, kind) }
        val warnings = LinkedHashMap<Pair<WarningCount.Kind, String?>, Int>()
        var sample: SamplePlan? = null
        var sampleIndex = Int.MAX_VALUE
        var checked = 0
        var lastEmit = 0L
        val lock = Mutex()
        val queue = Channel<Int>(Channel.UNLIMITED)
        photos.indices.forEach { queue.trySend(it) }
        queue.close()

        suspend fun snapshot() = lock.withLock { summarize(outcomes, reasons, checked, warnings, sample) }

        send(snapshot())
        val edit = operation.toEditOperation()
        coroutineScope {
            repeat(concurrency.coerceAtLeast(1)) {
                launch {
                    for (index in queue) {
                        val ref = photos[index]
                        val result = try {
                            val report = readReport(ref)
                            report to planner().plan(report, listOf(edit))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        val emit = lock.withLock {
                            checked++
                            if (result == null) {
                                outcomes[index] = PreviewOutcome.Unreadable
                            } else {
                                val (report, plan) = result
                                outcomes[index] = if (plan.changes.isEmpty) PreviewOutcome.Unchanged else routeOf(ref, report.format, kind)
                                reasons[index] = skipReason(ref, report.format, kind)
                                plan.warnings.forEach { text ->
                                    val key = warningKey(text)
                                    warnings[key] = (warnings[key] ?: 0) + 1
                                }
                                if (plan.diff.isNotEmpty() && index < sampleIndex) {
                                    sampleIndex = index
                                    sample = SamplePlan(ref, plan.diff)
                                }
                            }
                            val now = clock()
                            (now - lastEmit >= EMIT_INTERVAL_MS).also { if (it) lastEmit = now }
                        }
                        if (emit) send(snapshot())
                    }
                }
            }
        }
        send(snapshot())
    }

    private fun routeOf(ref: PhotoRef, format: ImageFormat?, kind: BatchKind): PreviewOutcome {
        val decision = BatchRouting.route(ref, format, kind, canWrite, hasCopyFolder = true)
        return when (decision.route) {
            BatchRoute.InPlace -> PreviewOutcome.InPlace
            BatchRoute.Copy -> PreviewOutcome.CopyNeedsFolder
            BatchRoute.Sidecar -> if (ref.parentDocumentUri == null) PreviewOutcome.SidecarNeedsFolder else PreviewOutcome.Sidecar
            BatchRoute.Skip -> PreviewOutcome.Skipped
        }
    }

    private fun skipReason(ref: PhotoRef, format: ImageFormat?, kind: BatchKind): BatchItemReason? =
        BatchRouting.route(ref, format, kind, canWrite, hasCopyFolder = true).reason

    private fun warningKey(text: String): Pair<WarningCount.Kind, String?> =
        if (text.startsWith(NO_CAPTURE_DATE_PREFIX)) WarningCount.Kind.NoCaptureDate to null else WarningCount.Kind.Other to text

    private fun summarize(
        outcomes: Array<PreviewOutcome>,
        reasons: Array<BatchItemReason?>,
        checked: Int,
        warnings: Map<Pair<WarningCount.Kind, String?>, Int>,
        sample: SamplePlan?,
    ): BatchPreview {
        val counts = IntArray(PreviewOutcome.entries.size)
        val skipReasons = HashMap<BatchItemReason, Int>()
        outcomes.forEachIndexed { index, outcome ->
            counts[outcome.ordinal]++
            if (outcome == PreviewOutcome.Skipped) {
                val reason = reasons.getOrNull(index) ?: BatchItemReason.UnsupportedFormat
                skipReasons[reason] = (skipReasons[reason] ?: 0) + 1
            }
        }
        return BatchPreview(
            total = outcomes.size,
            checked = checked,
            inPlace = counts[PreviewOutcome.InPlace.ordinal],
            sidecars = counts[PreviewOutcome.Sidecar.ordinal],
            sidecarsNeedingFolder = counts[PreviewOutcome.SidecarNeedsFolder.ordinal],
            copiesNeedingFolder = counts[PreviewOutcome.CopyNeedsFolder.ordinal],
            skipped = counts[PreviewOutcome.Skipped.ordinal],
            unchanged = counts[PreviewOutcome.Unchanged.ordinal],
            unreadable = counts[PreviewOutcome.Unreadable.ordinal],
            skipReasons = skipReasons,
            warnings = warnings.map { (key, count) -> WarningCount(key.first, count, key.second) }.sortedByDescending { it.count },
            sample = sample,
        )
    }

    companion object {
        const val DEFAULT_CONCURRENCY = 4
        private const val EMIT_INTERVAL_MS = 150L
        private const val NO_CAPTURE_DATE_PREFIX = "There is no capture date"
    }
}
