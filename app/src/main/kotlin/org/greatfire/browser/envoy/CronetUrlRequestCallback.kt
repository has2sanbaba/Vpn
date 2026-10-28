package org.greatfire.browser.envoy

import android.os.ConditionVariable
import android.util.Log
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.Locale

class CronetUrlRequestCallback(
    private val originalRequest: Request
) : UrlRequest.Callback() {

    private var redirectCount = 0
    private var response: Response = Response.Builder()
        .sentRequestAtMillis(System.currentTimeMillis())
        .request(originalRequest)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .build()

    private var ioException: IOException? = null
    private val condition = ConditionVariable()
    private val outputStream = ByteArrayOutputStream()
    private val receiveChannel = Channels.newChannel(outputStream)

    @Throws(IOException::class)
    fun blockForResponse(timeoutMs: Long = 25_000L): Response {
        val opened = condition.block(timeoutMs)
        if (!opened) {
            throw IOException("Cronet request timed out after ${timeoutMs}ms")
        }
        ioException?.let { throw it }
        return response
    }

    override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
        if (redirectCount >= 20) {
            request.cancel()
            return
        }
        redirectCount++
        request.followRedirect()
    }

    override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
        response = toOkResponse(response, info)
        request.read(ByteBuffer.allocateDirect(32 * 1024))
    }

    override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
        byteBuffer.flip()
        receiveChannel.write(byteBuffer)
        byteBuffer.clear()
        request.read(byteBuffer)
    }

    override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
        val contentType = response.header("content-type") ?: "text/html; charset=utf-8"
        val body = outputStream.toByteArray().toResponseBody(contentType.toMediaTypeOrNull())
        val updatedReq = runCatching {
            originalRequest.newBuilder().url(info.url).build()
        }.getOrDefault(originalRequest)

        response = response.newBuilder()
            .body(body)
            .request(updatedReq)
            .build()
        condition.open()
    }

    override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException?) {
        ioException = IOException("Cronet request failed: ${error?.message}", error)
        condition.open()
    }

    override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
        ioException = IOException("Cronet request canceled")
        condition.open()
    }

    companion object {
        private const val TAG = "CronetCallback"

        private fun toOkResponse(base: Response, info: UrlResponseInfo): Response {
            val proto = info.negotiatedProtocol.lowercase(Locale.ENGLISH)
            val protocol = when {
                proto.contains("quic") || proto.contains("h3") -> Protocol.QUIC
                proto.contains("h2") -> Protocol.HTTP_2
                else -> Protocol.HTTP_1_1
            }
            val headersBuilder = Headers.Builder()
            for (entry in info.allHeadersAsList) {
                val k = entry.key
                val v = entry.value ?: continue
                if (k == null || k.equals("content-encoding", ignoreCase = true)) continue
                try {
                    headersBuilder.add(k, v)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Skipped invalid header $k")
                }
            }
            return base.newBuilder()
                .receivedResponseAtMillis(System.currentTimeMillis())
                .protocol(protocol)
                .code(info.httpStatusCode)
                .message(info.httpStatusText.ifBlank { "OK" })
                .headers(headersBuilder.build())
                .build()
        }
    }
}
