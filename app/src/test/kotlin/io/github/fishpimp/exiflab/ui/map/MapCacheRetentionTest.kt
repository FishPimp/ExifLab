package io.github.fishpimp.exiflab.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MapCacheRetentionTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `the tile cache is emptied when its age is unknown or past the period`() {
        assertThat(MapCacheRetention.isDue(startedAt = 0L, now = now)).isTrue()
        assertThat(MapCacheRetention.isDue(startedAt = now - MapCacheRetention.PERIOD_MS, now = now)).isTrue()
        assertThat(MapCacheRetention.isDue(startedAt = now - MapCacheRetention.PERIOD_MS + 1, now = now)).isFalse()
        // A clock set back must not keep the cache forever.
        assertThat(MapCacheRetention.isDue(startedAt = now + 1, now = now)).isTrue()
    }
}
