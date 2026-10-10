package io.github.fishpimp.exiflab.data.backup

import io.github.fishpimp.exiflab.data.settings.BackupRetention

/**
 * One backup as the pruner sees it.
 *
 * @property targetKey the file the backup was taken of (photo or sidecar URI); "latest backup of
 *   a photo" is decided per key.
 * @property protected never pruned automatically: the backup of a save in progress, or of a save
 *   whose target could not be put back (the backup is then the only good copy).
 */
data class PruneCandidate(
    val path: String,
    val size: Long,
    val createdAt: Long,
    val targetKey: String,
    val protected: Boolean,
)

/** Decides which backups to delete. Pure, so the rules are unit-tested without files. */
object BackupPruner {
    /**
     * Backups to delete, given the [retention] and a total [sizeCapBytes]:
     * 1. every unprotected backup older than the retention;
     * 2. then, while the remaining backups take more than the cap, the oldest unprotected ones,
     *    except the latest backup of each target (which is younger than the retention by now).
     *
     * Protected backups are never selected but still count towards the cap.
     */
    fun select(
        candidates: List<PruneCandidate>,
        retention: BackupRetention,
        sizeCapBytes: Long,
        now: Long,
    ): Set<String> {
        val retentionMillis = retention.millis
        val expired = candidates
            .filter { !it.protected && retentionMillis != null && now - it.createdAt > retentionMillis }
            .mapTo(LinkedHashSet()) { it.path }

        val remaining = candidates.filter { it.path !in expired }
        var total = remaining.sumOf { it.size }
        if (total <= sizeCapBytes) return expired

        val latestPerTarget = remaining
            .groupBy { it.targetKey }
            .values
            .mapTo(HashSet()) { group -> group.maxWith(compareBy<PruneCandidate> { it.createdAt }.thenBy { it.path }).path }
        val selected = LinkedHashSet(expired)
        remaining
            .filter { !it.protected && it.path !in latestPerTarget }
            .sortedWith(compareBy<PruneCandidate> { it.createdAt }.thenBy { it.path })
            .forEach { candidate ->
                if (total <= sizeCapBytes) return selected
                selected += candidate.path
                total -= candidate.size
            }
        return selected
    }
}
