package io.github.fishpimp.exiflab.data.places

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.network.NetworkPolicy
import io.github.fishpimp.exiflab.data.network.createHttpClient
import io.github.fishpimp.exiflab.data.network.exifLabUserAgent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.io.IOException
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class PlaceSearchRepositoryTest {
    private var offline = false
    private val requests = mutableListOf<Request>()

    /** What the fake server answers; defaults to the recorded Stockholm search. */
    private var respond: (Request) -> Response = { request -> ok(request, sample("stockholm.json")) }

    @Test
    fun `sends the query and result limit to Photon, with no language for Swedish`() = runBlocking {
        val result = repository(language = "sv").search("  Stockholm ")

        assertThat(result).isInstanceOf(PlaceSearchResult.Success::class.java)
        assertThat((result as PlaceSearchResult.Success).places.first().name).isEqualTo("Stockholm")
        val url = requests.single().url
        assertThat(url.scheme).isEqualTo("https")
        assertThat(url.host).isEqualTo("photon.komoot.io")
        assertThat(url.encodedPath).isEqualTo("/api/")
        assertThat(url.queryParameter("q")).isEqualTo("Stockholm")
        assertThat(url.queryParameter("limit")).isEqualTo("8")
        assertThat(url.queryParameterNames).doesNotContain("lang")
    }

    @Test
    fun `asks for English, German or French labels only when the app uses that language`() = runBlocking {
        listOf("en", "de", "fr", "sv", "fi", null).forEach { language -> repository(language).search("Paris") }
        assertThat(requests.map { it.url.queryParameter("lang") })
            .containsExactly("en", "de", "fr", null, null, null).inOrder()
    }

    @Test
    fun `search text is encoded, not interpolated`() = runBlocking {
        repository(language = "sv").search("Gamla stan & Söder?lang=xx")
        val url = requests.single().url
        assertThat(url.queryParameter("q")).isEqualTo("Gamla stan & Söder?lang=xx")
        assertThat(url.queryParameterNames).doesNotContain("lang")
    }

    @Test
    fun `short or blank queries return nothing without a request`() = runBlocking {
        val repository = repository()
        listOf("", "   ", "a", " b ").forEach { query ->
            assertThat(repository.search(query)).isEqualTo(PlaceSearchResult.Success(emptyList()))
        }
        assertThat(requests).isEmpty()
    }

    @Test
    fun `offline mode fails fast without sending anything`() = runBlocking {
        offline = true
        assertThat(repository().search("Stockholm")).isEqualTo(PlaceSearchResult.Failure(PlaceSearchError.Offline))
        assertThat(requests).isEmpty()
    }

    @Test
    fun `offline mode switched on mid-search is reported as offline`() = runBlocking {
        // The repository's own check still says online; the client's policy already says offline.
        val repository = repository(isOffline = { false })
        offline = true
        assertThat(repository.search("Stockholm")).isEqualTo(PlaceSearchResult.Failure(PlaceSearchError.Offline))
        assertThat(requests).isEmpty()
    }

    @Test
    fun `server errors are reported as server failures`() = runBlocking {
        listOf(500, 502, 429, 400).forEach { code ->
            respond = { request -> response(request, code, "{\"message\":\"error\"}") }
            assertThat(repository().search("Stockholm")).isEqualTo(PlaceSearchResult.Failure(PlaceSearchError.Server))
        }
    }

    @Test
    fun `an unreadable answer is a server failure`() = runBlocking {
        respond = { request -> ok(request, sample("bad_gateway.html")) }
        assertThat(repository().search("Stockholm")).isEqualTo(PlaceSearchResult.Failure(PlaceSearchError.Server))
    }

    @Test
    fun `connection problems are network failures`() = runBlocking {
        respond = { throw IOException("timeout") }
        assertThat(repository().search("Stockholm")).isEqualTo(PlaceSearchResult.Failure(PlaceSearchError.Network))
    }

    @Test
    fun `no results is a success with an empty list`() = runBlocking {
        respond = { request -> ok(request, sample("empty.json")) }
        assertThat(repository().search("Xyzzy")).isEqualTo(PlaceSearchResult.Success(emptyList()))
    }

    @Test
    fun `cancelling the search cancels the request`() = runBlocking {
        val started = CountDownLatch(1)
        var call: Call? = null
        respond = { request ->
            started.countDown()
            // Hold the request open, like a slow server, until OkHttp reports the cancel.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (call?.isCanceled() != true && System.nanoTime() < deadline) Thread.sleep(5)
            ok(request, sample("stockholm.json"))
        }
        val client = baseClient().newBuilder()
            .addInterceptor(Interceptor { chain -> call = chain.call(); chain.proceed(chain.request()) })
            .addInterceptor(fakeServer())
            .build()
        val repository = PlaceSearchRepository(client, isOffline = { offline }, appLanguage = { "en" }, ioDispatcher = Dispatchers.IO)

        val search = async(Dispatchers.Default) { repository.search("Stockholm") }
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue()
        search.cancel()
        withTimeout(10_000) { while (call?.isCanceled() != true) delay(5) }
        assertThat(call!!.isCanceled()).isTrue()
    }

    @Test
    fun `search as you type waits for a pause and only sends the latest text`() = runTest {
        val repository = repository(inline = true, dispatcher = StandardTestDispatcher(testScheduler))
        val queries = MutableSharedFlow<String>()
        val states = mutableListOf<PlaceSearchState>()
        val collecting = launch { repository.searchAsYouType(queries).collect { states += it } }
        runCurrent()

        queries.emit("S")
        runCurrent()
        listOf("St", "Sto", "Stock", "Stockholm").forEach { text ->
            queries.emit(text)
            advanceTimeBy(100)
        }
        advanceTimeBy(PlaceSearchRepository.DEBOUNCE_MILLIS + 1)
        runCurrent()

        assertThat(requests.map { it.url.queryParameter("q") }).containsExactly("Stockholm")
        assertThat(states[0]).isEqualTo(PlaceSearchState.Idle)
        assertThat(states[1]).isEqualTo(PlaceSearchState.Searching("Stockholm"))
        val results = states[2] as PlaceSearchState.Results
        assertThat(results.query).isEqualTo("Stockholm")
        assertThat(results.places).isNotEmpty()

        // Clearing the field ends the search right away.
        queries.emit("")
        runCurrent()
        assertThat(states.last()).isEqualTo(PlaceSearchState.Idle)
        collecting.cancel()
    }

    @Test
    fun `search as you type reports failures with their query`() = runTest {
        offline = true
        val repository = repository(inline = true, dispatcher = StandardTestDispatcher(testScheduler))
        val queries = MutableSharedFlow<String>()
        val states = mutableListOf<PlaceSearchState>()
        val collecting = launch { repository.searchAsYouType(queries).collect { states += it } }
        runCurrent()

        queries.emit("Uppsala")
        advanceTimeBy(PlaceSearchRepository.DEBOUNCE_MILLIS + 1)
        runCurrent()

        assertThat(states.last()).isEqualTo(PlaceSearchState.Failed("Uppsala", PlaceSearchError.Offline))
        collecting.cancel()
    }

    private fun repository(
        language: String? = "en",
        isOffline: suspend () -> Boolean = { offline },
        inline: Boolean = false,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): PlaceSearchRepository {
        val client = baseClient(inline).newBuilder().addInterceptor(fakeServer()).build()
        return PlaceSearchRepository(client, isOffline = isOffline, appLanguage = { language }, ioDispatcher = dispatcher)
    }

    /** The production client and policy; [inline] runs calls on the calling thread for virtual-time tests. */
    private fun baseClient(inline: Boolean = false): OkHttpClient {
        val client = createHttpClient(NetworkPolicy(isOffline = { offline }), exifLabUserAgent("test"), cacheDirectory = null)
        return if (inline) client.newBuilder().dispatcher(Dispatcher(DirectExecutor())).build() else client
    }

    private fun fakeServer() = Interceptor { chain ->
        synchronized(requests) { requests += chain.request() }
        respond(chain.request())
    }

    private fun ok(request: Request, body: String) = response(request, 200, body)

    private fun response(request: Request, code: Int, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("HTTP $code")
        .body(body.toResponseBody())
        .build()
}

/** Runs each task immediately on the calling thread. */
private class DirectExecutor : AbstractExecutorService() {
    @Volatile private var shutdown = false

    override fun execute(command: Runnable) = command.run()
    override fun shutdown() { shutdown = true }
    override fun shutdownNow(): MutableList<Runnable> = mutableListOf<Runnable>().also { shutdown = true }
    override fun isShutdown() = shutdown
    override fun isTerminated() = shutdown
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}
