package io.github.fishpimp.exiflab.data.backup

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.settings.BackupRetention
import org.junit.Test
import java.util.concurrent.TimeUnit

class BackupPrunerTest {
    private val now = 1_800_000_000_000L
    private fun daysAgo(days: Int) = now - TimeUnit.DAYS.toMillis(days.toLong())
    private val big = Long.MAX_VALUE

    private fun backup(path: String, ageDays: Int, size: Long = 100, target: String = path, protected: Boolean = false) =
        PruneCandidate(path, size, daysAgo(ageDays), target, protected)

    @Test
    fun `prunes backups older than the retention`() {
        val candidates = listOf(backup("old", 31), backup("fresh", 29), backup("week", 8))

        assertThat(BackupPruner.select(candidates, BackupRetention.Days30, big, now)).containsExactly("old")
        assertThat(BackupPruner.select(candidates, BackupRetention.Days7, big, now)).containsExactly("old", "fresh", "week")
        assertThat(BackupPruner.select(candidates, BackupRetention.Days90, big, now)).isEmpty()
        assertThat(BackupPruner.select(candidates, BackupRetention.Forever, big, now)).isEmpty()
    }

    @Test
    fun `never prunes protected backups, however old`() {
        val candidates = listOf(backup("pending", 400, protected = true), backup("old", 400))

        assertThat(BackupPruner.select(candidates, BackupRetention.Days7, big, now)).containsExactly("old")
        // Kept forever and the only backup of its photo: the size cap cannot take it either.
        assertThat(BackupPruner.select(candidates, BackupRetention.Forever, sizeCapBytes = 0, now = now)).isEmpty()
    }

    @Test
    fun `over the size cap prunes oldest first until it fits`() {
        val candidates = listOf(
            backup("a1", 10, target = "a"),
            backup("a2", 5, target = "a"),
            backup("a3", 1, target = "a"),
            backup("b1", 8, target = "b"),
            backup("b2", 2, target = "b"),
        )

        // 500 bytes in total; 300 allowed: drop the two oldest that are not the latest of their photo.
        assertThat(BackupPruner.select(candidates, BackupRetention.Days30, sizeCapBytes = 300, now = now))
            .containsExactly("a1", "b1").inOrder()
    }

    @Test
    fun `the latest backup of each photo survives the size cap`() {
        val candidates = listOf(
            backup("a1", 3, size = 1_000, target = "a"),
            backup("a2", 2, size = 1_000, target = "a"),
            backup("b1", 1, size = 1_000, target = "b"),
        )

        assertThat(BackupPruner.select(candidates, BackupRetention.Days30, sizeCapBytes = 10, now = now)).containsExactly("a1")
        assertThat(BackupPruner.select(candidates, BackupRetention.Forever, sizeCapBytes = 10, now = now)).containsExactly("a1")
    }

    @Test
    fun `protected backups count towards the cap`() {
        val candidates = listOf(
            backup("pending", 0, size = 900, target = "p", protected = true),
            backup("x1", 3, size = 100, target = "x"),
            backup("x2", 2, size = 100, target = "x"),
        )

        assertThat(BackupPruner.select(candidates, BackupRetention.Days30, sizeCapBytes = 1_000, now = now)).containsExactly("x1")
    }

    @Test
    fun `nothing to prune under the cap and within the retention`() {
        val candidates = listOf(backup("a", 1), backup("b", 2))
        assertThat(BackupPruner.select(candidates, BackupRetention.Days30, sizeCapBytes = 1_000, now = now)).isEmpty()
    }
}
