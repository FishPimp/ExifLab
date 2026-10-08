package io.github.fishpimp.exiflab.engine

import io.github.fishpimp.exiflab.engine.plan.LowLevelEdits
import io.github.fishpimp.exiflab.model.DngWriteMode
import io.github.fishpimp.exiflab.model.ImageFormat
import io.github.fishpimp.exiflab.model.PlannedChange
import io.github.fishpimp.exiflab.model.WriteDestination

public class ReadOptions(
    /** Decode MakerNotes (slower on some RAW files). */
    public val includeMakerNotes: Boolean = true,
    /** Contents of an existing XMP sidecar, shown as its own directory group. */
    public val sidecarXmp: ByteArray? = null,
)

public class PlanOptions(
    public val dngWriteMode: DngWriteMode = DngWriteMode.IN_FILE,
    /** Force sidecar output even for in-file formats (used when the file itself is read-only). */
    public val forceSidecar: Boolean = false,
)

/**
 * The result of planning: what will change ([changes], for the diff UI) and how (internal low-level edits).
 * Plans are cheap to compute and must be recomputed from a fresh read before every write.
 */
public class EditPlan internal constructor(
    public val format: ImageFormat,
    public val destination: WriteDestination,
    public val changes: List<PlannedChange>,
    public val warnings: List<String>,
    internal val edits: LowLevelEdits,
) {
    public val isEmpty: Boolean get() = changes.isEmpty() && edits.isEmpty()
}

public data class WriteReport(
    public val bytesWritten: Long,
    /** Bytes of superseded metadata that were overwritten with zeros. */
    public val zeroedBytes: Long,
    public val notes: List<String> = emptyList(),
)

public data class VerificationReport(
    public val ok: Boolean,
    public val imageDataHashBefore: String?,
    public val imageDataHashAfter: String?,
    /** Human-readable problems (English, for logs and the history detail view). Empty when [ok]. */
    public val problems: List<String>,
)

/** Result of parsing user input for a tag editor. */
public sealed interface ParsedValue {
    public data class Valid(val value: io.github.fishpimp.exiflab.model.TagValue, val normalized: String) : ParsedValue
    public data class Invalid(val reason: InvalidReason) : ParsedValue
}

/** Reasons are codes so the app can show localised messages. */
public enum class InvalidReason {
    EMPTY, NOT_ASCII, TOO_LONG, NOT_A_NUMBER, OUT_OF_RANGE, NEGATIVE_NOT_ALLOWED, BAD_DATE, BAD_SUBSECONDS, BAD_OFFSET, NOT_AN_OPTION
}
