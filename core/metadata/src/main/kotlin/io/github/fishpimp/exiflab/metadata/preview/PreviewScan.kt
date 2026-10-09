package io.github.fishpimp.exiflab.metadata.preview

/** Where an embedded JPEG might be, before validation. */
internal data class PreviewCandidate(val offset: Long, val length: Long)

/** Everything a container scan found: candidate JPEGs and the orientation of the main image. */
internal class PreviewScan(val candidates: List<PreviewCandidate>, val orientation: Int?) {
    companion object {
        val EMPTY = PreviewScan(emptyList(), null)
    }
}
