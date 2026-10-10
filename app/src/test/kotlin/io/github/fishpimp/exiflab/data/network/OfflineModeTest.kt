package io.github.fishpimp.exiflab.data.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OfflineModeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `readers wait for the stored value instead of assuming online`() {
        val stored = MutableStateFlow<Boolean?>(null)
        val offlineMode = OfflineMode(stored.filterNotNull(), scope)
        assertThat(offlineMode.currentOrNull).isNull()

        val executor = Executors.newSingleThreadExecutor()
        try {
            val read = executor.submit<Boolean> { offlineMode.currentBlocking() }
            Thread.sleep(100)
            assertThat(read.isDone).isFalse()

            stored.value = true
            assertThat(read.get(5, TimeUnit.SECONDS)).isTrue()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `changes are visible right away`() = runBlocking {
        val stored = MutableStateFlow(false)
        val offlineMode = OfflineMode(stored, scope)
        assertThat(withTimeout(5_000) { offlineMode.current() }).isFalse()

        stored.value = true
        withTimeout(5_000) { while (offlineMode.currentOrNull != true) delay(10) }
        assertThat(offlineMode.currentBlocking()).isTrue()
        assertThat(offlineMode.current()).isTrue()
    }
}
