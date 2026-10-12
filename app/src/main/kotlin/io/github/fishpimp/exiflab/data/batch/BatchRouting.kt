package io.github.fishpimp.exiflab.data.batch

import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.photos.format
import io.github.fishpimp.exiflab.metadata.ImageFormat

/** How a batch saves one photo. */
enum class BatchRoute {
    /** Rewritten in place: a writable photo in a format the writers handle. */
    InPlace,

    /** An XMP sidecar: RAW (DNG included) and formats the writers cannot rewrite. */
    Sidecar,

    /** A new copy in the chosen folder: read-only photos (picker, shared, read-only folders). */
    Copy,

    /** Not written; see [RouteDecision.reason]. */
    Skip,
}

/** The [route] for one photo, with the reason when it is skipped. */
data class RouteDecision(val route: BatchRoute, val reason: BatchItemReason? = null) {
    companion object {
        val InPlace = RouteDecision(BatchRoute.InPlace)
        val Sidecar = RouteDecision(BatchRoute.Sidecar)
        val Copy = RouteDecision(BatchRoute.Copy)
        fun skip(reason: BatchItemReason) = RouteDecision(BatchRoute.Skip, reason)
    }
}

/**
 * Decides where each photo of a batch is saved, the way the single-photo editor would: RAW and
 * formats ExifLab cannot rewrite get a sidecar, writable photos are edited in place, read-only
 * photos become copies in [BatchJob.copyFolderUri] (or are skipped without one). Pure, so the
 * preview and the worker agree.
 */
object BatchRouting {
    /**
     * @param reportFormat the format the reader sniffed, when the photo was read; wins over an
     *   unknown extension.
     * @param canWrite whether the writers handle a format; HEIF switches from sidecar to in place
     *   as soon as its writer reports it can write.
     * @param hasCopyFolder whether a folder for copies (and for sidecars of photos outside a
     *   granted folder) was chosen.
     */
    fun route(
        ref: PhotoRef,
        reportFormat: ImageFormat?,
        kind: BatchKind,
        canWrite: (ImageFormat) -> Boolean,
        hasCopyFolder: Boolean,
    ): RouteDecision {
        val format = ref.format.takeIf { it != ImageFormat.Unknown } ?: reportFormat ?: ImageFormat.Unknown
        val sidecarOnly = format.isRaw || reportFormat?.isRaw == true || !canWrite(format)
        return when {
            // A sidecar can add or change values, but it cannot take anything out of the file.
            sidecarOnly && kind == BatchKind.Strip -> RouteDecision.skip(BatchItemReason.CannotStrip)
            // Sidecars go next to photos from granted folders; others need the chosen folder.
            sidecarOnly && ref.parentDocumentUri == null && !hasCopyFolder -> RouteDecision.skip(BatchItemReason.NoSidecarPlace)
            sidecarOnly -> RouteDecision.Sidecar
            ref.writable -> RouteDecision.InPlace
            hasCopyFolder -> RouteDecision.Copy
            else -> RouteDecision.skip(BatchItemReason.ReadOnly)
        }
    }

    /**
     * True when [ref] needs the copy folder: a read-only photo that becomes a copy, or a sidecar
     * that cannot go next to its photo.
     */
    fun needsFolder(ref: PhotoRef, reportFormat: ImageFormat?, kind: BatchKind, canWrite: (ImageFormat) -> Boolean): Boolean {
        val decision = route(ref, reportFormat, kind, canWrite, hasCopyFolder = true)
        return decision.route == BatchRoute.Copy ||
            (decision.route == BatchRoute.Sidecar && ref.parentDocumentUri == null)
    }
}
