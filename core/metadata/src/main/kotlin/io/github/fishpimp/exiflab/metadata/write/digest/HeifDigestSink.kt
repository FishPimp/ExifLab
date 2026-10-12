package io.github.fishpimp.exiflab.metadata.write.digest

import io.github.fishpimp.exiflab.metadata.write.heif.HeifDigestPlan
import java.security.MessageDigest

/**
 * HEIF/AVIF: hashes the byte ranges of [plan] (item extents and track chunks) as the whole file
 * streams through, each range on its own so that overlapping and out-of-order ranges work, then
 * combines them in item id order. The plan comes from the file's own `meta` and `moov` boxes, so
 * a source and a rewritten output with moved data produce the same digest.
 */
internal class HeifDigestSink(private val plan: HeifDigestPlan) : ImageDigestSink() {
    private val order = plan.starts.indices.sortedBy { plan.starts[it] }
    private val digests = List(plan.starts.size) { MessageDigest.getInstance("SHA-256") }
    private val fed = LongArray(plan.starts.size)
    private val active = ArrayList<Int>()
    private var next = 0
    private var position = 0L

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        val end = position + len
        while (next < order.size && plan.starts[order[next]] < end) active += order[next++]
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val range = iterator.next()
            val rangeStart = plan.starts[range]
            val rangeEnd = rangeStart + plan.lengths[range]
            val from = maxOf(rangeStart, position)
            val to = minOf(rangeEnd, end)
            if (to > from) {
                digests[range].update(b, off + (from - position).toInt(), (to - from).toInt())
                fed[range] += to - from
            }
            if (rangeEnd <= end) iterator.remove()
        }
        position = end
    }

    override fun hex(): String = plan.finish(digests.map { it.digest() }, fed)
}
