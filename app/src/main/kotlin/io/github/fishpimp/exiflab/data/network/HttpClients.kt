package io.github.fishpimp.exiflab.data.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** The User-Agent ExifLab sends with every request. It names the app and nothing about the user. */
fun exifLabUserAgent(versionName: String): String =
    "ExifLab/$versionName (Android; +https://github.com/FishPimp/ExifLab)"

/**
 * Builds the app's one [OkHttpClient]. Every request goes through [policy] twice: once before
 * anything touches the network or the cache, and again for every network hop, so a redirect
 * cannot lead to a host outside the allowlist.
 *
 * @param cacheDirectory where the small HTTP cache lives, or null for no cache.
 */
fun createHttpClient(
    policy: NetworkPolicy,
    userAgent: String,
    cacheDirectory: File?,
): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(PolicyInterceptor(policy))
    .addInterceptor(UserAgentInterceptor(userAgent))
    .addNetworkInterceptor(PolicyInterceptor(policy))
    .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .apply { if (cacheDirectory != null) cache(Cache(cacheDirectory, CACHE_BYTES)) }
    .build()

/** Refuses requests the [NetworkPolicy] does not allow. */
internal class PolicyInterceptor(private val policy: NetworkPolicy) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        policy.check(request.url)
        return chain.proceed(request)
    }
}

/** Replaces any User-Agent (MapLibre sets its own) with the app's. */
internal class UserAgentInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
}

/**
 * Runs the call without blocking a thread. Cancelling the coroutine cancels the call, so a
 * search that is superseded by newer input stops right away.
 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        },
    )
}

private const val CONNECT_TIMEOUT_SECONDS = 10L
private const val READ_TIMEOUT_SECONDS = 20L
private const val CALL_TIMEOUT_SECONDS = 30L
private const val CACHE_BYTES = 10L * 1024 * 1024
