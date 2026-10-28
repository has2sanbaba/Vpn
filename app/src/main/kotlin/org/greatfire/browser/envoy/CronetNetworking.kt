package org.greatfire.browser.envoy

import android.content.Context
import android.util.Log
import okhttp3.Request
import okio.Buffer
import org.chromium.net.CronetEngine
import org.chromium.net.UploadDataProviders
import org.chromium.net.UrlRequest
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * GreatFire Envoy patched Chromium Cronet engine wrapper.
 * Supports envoy:// URLs, socks5:// proxies, ECH, HTTP/2, QUIC, Brotli,
 * and Geneva packet-level censorship evasion strategies (SetStrategy 1..6).
 */
object CronetNetworking {
    private const val TAG = "GreatFireCronet"

    @Volatile
    private var cronetEngine: CronetEngine? = null
    private val executorService: ExecutorService = Executors.newCachedThreadPool()

    @JvmStatic
    fun currentEngine(): CronetEngine? = cronetEngine

    @Synchronized
    fun initializeEngine(
        context: Context,
        envoyUrl: String? = null,
        genevaStrategy: Int = 0,
        forceRebuild: Boolean = true
    ): CronetEngine? {
        if (cronetEngine != null && !forceRebuild) {
            return cronetEngine
        }

        val previous = cronetEngine
        cronetEngine = null
        runCatching { previous?.shutdown() }

        return try {
            val cacheDir = File(context.cacheDir, "gf-cronet-cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            var builder = CronetEngine.Builder(context.applicationContext)
                .enableBrotli(true)
                .enableHttp2(true)
                .enableQuic(true)
                .setStoragePath(cacheDir.absolutePath)
                .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, 16L * 1024L * 1024L)

            if (!envoyUrl.isNullOrBlank()) {
                builder = builder.setEnvoyUrl(envoyUrl)
            }
            if (genevaStrategy > 0) {
                builder = builder.SetStrategy(genevaStrategy)
            }

            val built = builder.build()
            cronetEngine = built
            Log.i(TAG, "Initialized GreatFire Cronet: ${built.versionString}, strategy=$genevaStrategy")
            built
        } catch (t: Throwable) {
            Log.e(TAG, "Cronet initialization unavailable on this runtime, using OkHttp+GfProxy fallback", t)
            null
        }
    }

    @Throws(IOException::class)
    fun buildRequest(
        request: Request,
        callback: UrlRequest.Callback,
        engine: CronetEngine? = cronetEngine
    ): UrlRequest {
        val activeEngine = engine ?: throw IOException("CronetEngine not initialized")
        val url = request.url.toString()
        val requestBuilder = activeEngine.newUrlRequestBuilder(url, callback, executorService)
        requestBuilder.setHttpMethod(request.method)

        request.headers.forEach { (name, value) ->
            if (name.lowercase(Locale.ENGLISH) != "accept-encoding") {
                requestBuilder.addHeader(name, value)
            }
        }

        val body = request.body
        if (body != null) {
            body.contentType()?.let { contentType ->
                if (request.header("Content-Type") == null) {
                    requestBuilder.addHeader("Content-Type", contentType.toString())
                }
            }
            val buffer = Buffer()
            body.writeTo(buffer)
            val uploadProvider = UploadDataProviders.create(buffer.readByteArray())
            requestBuilder.setUploadDataProvider(uploadProvider, executorService)
        }

        return requestBuilder.build()
    }
}
