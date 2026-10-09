package io.github.fishpimp.exiflab.data.network

import com.google.common.truth.Truth.assertThat
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * The app's HTTP client must reach only the allowlisted hosts, only over HTTPS, and nothing at
 * all in offline mode. A fake terminal interceptor stands in for the network, so these tests
 * never leave the machine and can see exactly which requests would have gone out.
 */
class HttpClientPolicyTest {
    private var offline = false
    private val sent = mutableListOf<Request>()
    private val servers = mutableListOf<MockWebServer>()

    /** The production client, with the network replaced by a recorder that answers 200. */
    private val client: OkHttpClient = createHttpClient(
        policy = NetworkPolicy(isOffline = { offline }),
        userAgent = exifLabUserAgent("1.2.3"),
        cacheDirectory = null,
    ).newBuilder()
        .addInterceptor(
            Interceptor { chain ->
                sent += chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody())
                    .build()
            },
        )
        .build()

    @After
    fun closeServers() = servers.forEach { it.close() }

    @Test
    fun `map tiles and place search are allowed`() {
        assertThat(get("https://tiles.openfreemap.org/planet").code).isEqualTo(200)
        assertThat(get("https://photon.komoot.io/api/?q=Stockholm&limit=8").code).isEqualTo(200)
        assertThat(sent.map { it.url.host }).containsExactly("tiles.openfreemap.org", "photon.komoot.io").inOrder()
    }

    @Test
    fun `hosts outside the allowlist are refused before anything is sent`() {
        val blocked = listOf(
            "https://example.com/",
            "https://tile.openstreetmap.org/1/1/1.png",
            "https://www.google-analytics.com/collect",
            // Look-alikes: subdomains, suffixes and prefixes of allowed hosts.
            "https://cdn.tiles.openfreemap.org/planet",
            "https://tiles.openfreemap.org.example.com/planet",
            "https://eviltiles.openfreemap.org/planet",
            "https://photon.komoot.io.attacker.net/api/",
            "https://openfreemap.org/",
            "https://komoot.io/",
        )
        blocked.forEach { url ->
            val error = assertThrows(HostNotAllowedException::class.java) { get(url) }
            assertThat(error.host).isEqualTo(Request.Builder().url(url).build().url.host)
        }
        assertThat(sent).isEmpty()
    }

    @Test
    fun `host matching ignores case`() {
        assertThat(get("https://TILES.OpenFreeMap.org/planet").code).isEqualTo(200)
    }

    @Test
    fun `plain http is refused even for allowed hosts`() {
        assertThrows(InsecureRequestException::class.java) { get("http://tiles.openfreemap.org/planet") }
        assertThrows(InsecureRequestException::class.java) { get("http://photon.komoot.io/api/?q=x") }
        assertThat(sent).isEmpty()
    }

    @Test
    fun `offline mode blocks every request, allowed hosts included`() {
        offline = true
        assertThrows(OfflineModeException::class.java) { get("https://tiles.openfreemap.org/planet") }
        assertThrows(OfflineModeException::class.java) { get("https://photon.komoot.io/api/?q=Paris") }
        assertThrows(OfflineModeException::class.java) { get("https://example.com/") }
        assertThat(sent).isEmpty()

        offline = false
        assertThat(get("https://tiles.openfreemap.org/planet").code).isEqualTo(200)
        assertThat(sent).hasSize(1)
    }

    @Test
    fun `blocked requests fail as IOExceptions, so callers like MapLibre handle them`() {
        offline = true
        val error = runCatching { get("https://tiles.openfreemap.org/planet") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IOException::class.java)
    }

    @Test
    fun `every request carries the app user agent, replacing any other`() {
        get("https://tiles.openfreemap.org/planet")
        client.newCall(
            Request.Builder()
                .url("https://tiles.openfreemap.org/planet/1/0/0.pbf")
                .header("User-Agent", "MapLibre Native/13.6.1 (Android)")
                .build(),
        ).execute().close()
        assertThat(sent.map { it.header("User-Agent") })
            .containsExactly(
                "ExifLab/1.2.3 (Android; +https://github.com/FishPimp/ExifLab)",
                "ExifLab/1.2.3 (Android; +https://github.com/FishPimp/ExifLab)",
            )
    }

    @Test
    fun `a redirect to a host outside the allowlist is refused before the request is sent`() {
        val allowed = MockWebServer().also(servers::add).apply { start() }
        val other = MockWebServer().also(servers::add).apply { start() }
        val allowedHost = allowed.url("/").host
        // Same machine, different host name: only the first is on this test's allowlist.
        val otherHost = if (allowedHost == "localhost") "127.0.0.1" else "localhost"
        val target = other.url("/leak").newBuilder().host(otherHost).build()
        allowed.enqueue(MockResponse.Builder().code(302).addHeader("Location", target.toString()).build())
        other.enqueue(MockResponse.Builder().code(200).body("leaked").build())

        val local = createHttpClient(
            policy = NetworkPolicy(isOffline = { false }, allowedHosts = setOf(allowedHost), requireHttps = false),
            userAgent = exifLabUserAgent("test"),
            cacheDirectory = null,
        )
        val error = assertThrows(HostNotAllowedException::class.java) {
            local.newCall(Request.Builder().url(allowed.url("/start")).build()).execute().close()
        }
        assertThat(error.host).isEqualTo(otherHost)
        assertThat(allowed.requestCount).isEqualTo(1)
        assertThat(other.requestCount).isEqualTo(0)
    }

    @Test
    fun `the production allowlist is exactly the two documented hosts`() {
        assertThat(AllowedHosts.all).containsExactly("tiles.openfreemap.org", "photon.komoot.io")
    }

    private fun get(url: String): Response =
        client.newCall(Request.Builder().url(url).build()).execute().also { it.close() }
}
