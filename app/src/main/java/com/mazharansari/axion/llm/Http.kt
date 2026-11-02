package com.mazharansari.axion.llm

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

object Http {
    const val UA = "Mozilla/5.0 (Linux; Android 16; Infinix X6885) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    /** One shared pool: connections and threads are reused by every request in the app. */
    /** Big file downloads (Ubuntu base): long reads, no overall cap. */
    val download: OkHttpClient by lazy { client.newBuilder().readTimeout(120, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build() }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .build()
    }

    val quick: OkHttpClient by lazy {
        client.newBuilder().readTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
    }

    /**
     * v0.5.2 speed: opens (DNS + TCP + TLS + HTTP/2) the connection to a provider while the user is still typing, so the
     * real request reuses it from the shared pool. A bare HEAD to the host root: no key, no body, no path.
     * At most once per host per 45 s (the pool keeps idle connections for 5 min).
     */
    private val warmed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile var prewarmCount = 0; private set
    fun prewarm(baseUrl: String, now: Long = System.currentTimeMillis()): Boolean {
        val u = baseUrl.toHttpUrlOrNull() ?: return false
        if (u.scheme != "https" && u.host != "127.0.0.1" && u.host != "localhost") return false
        val origin = "${u.scheme}://${u.host}:${u.port}/"
        val last = warmed[origin]
        if (last != null && now - last < 45_000) return false
        warmed[origin] = now
        prewarmCount++
        val warm = client.newBuilder().readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()   // same pool
        warm.newCall(okhttp3.Request.Builder().url(origin).head().header("User-Agent", UA).build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { warmed.remove(origin) }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { response.close() }
        })
        return true
    }
}


/**
 * v0.4 (mazhar 2:17 PM: "the stop button does nothing"). `Job.invokeOnCompletion { }` only runs once the job has
 * COMPLETED — and a cancelled job can't complete while its coroutine sits inside OkHttp's blocking read, so Stop waited
 * for the whole answer. Hooking the cancelling phase closes the socket at once; the blocked read throws and unwinds.
 */
@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
fun kotlinx.coroutines.Job.cancelHook(block: () -> Unit): kotlinx.coroutines.DisposableHandle =
    invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) block() }
