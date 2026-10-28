package org.greatfire.browser.envoy

import okhttp3.Interceptor
import okhttp3.Response
import org.chromium.net.CronetEngine
import java.io.IOException

/**
 * Routes OkHttp calls through GreatFire's patched CronetEngine when active,
 * falling back cleanly to the underlying OkHttp proxy chain otherwise.
 */
class CronetInterceptor(
    private val explicitEngine: CronetEngine? = null
) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val engine = explicitEngine ?: CronetNetworking.currentEngine()
        if (engine != null) {
            val callback = CronetUrlRequestCallback(chain.request())
            val req = CronetNetworking.buildRequest(chain.request(), callback, engine)
            req.start()
            return callback.blockForResponse()
        }
        return chain.proceed(chain.request())
    }
}
