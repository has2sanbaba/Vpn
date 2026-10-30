package org.greatfire.browser.vpn

import android.util.Log

/**
 * Standard Android NDK/JNI bridge to `libgfproxy.so` (`-buildmode=c-shared`).
 * Runs 100% in-process via `System.loadLibrary("gfproxy")` — never executes child binaries
 * or external shell processes.
 */
object NativeProxyBridge {
    private const val TAG = "NativeProxyBridge"

    val isLoaded: Boolean = try {
        System.loadLibrary("gfproxy")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "Unable to load JNI shared library libgfproxy.so: ${t.message}")
        false
    }

    @JvmStatic
    private external fun nativeStart(
        dataDirUtf8: ByteArray,
        caPemPathUtf8: ByteArray,
        fbConfigPathUtf8: ByteArray,
        customUriUtf8: ByteArray
    ): Int

    @JvmStatic
    private external fun nativeControl(
        methodUtf8: ByteArray,
        pathUtf8: ByteArray,
        bodyUtf8: ByteArray
    ): ByteArray?

    @JvmStatic
    private external fun nativeStop()

    fun startEngine(
        dataDir: String,
        caPemPath: String,
        fbConfigPath: String,
        customUri: String
    ): Int {
        if (!isLoaded) return -1
        return nativeStart(
            dataDir.toByteArray(Charsets.UTF_8),
            caPemPath.toByteArray(Charsets.UTF_8),
            fbConfigPath.toByteArray(Charsets.UTF_8),
            customUri.toByteArray(Charsets.UTF_8)
        )
    }

    fun controlRequest(method: String, path: String, bodyJson: String = ""): String {
        if (!isLoaded) return """{"ok":false,"error":"native library unavailable"}"""
        val out = nativeControl(
            method.toByteArray(Charsets.UTF_8),
            path.toByteArray(Charsets.UTF_8),
            bodyJson.toByteArray(Charsets.UTF_8)
        ) ?: return """{"ok":false,"error":"empty native response"}"""
        return String(out, Charsets.UTF_8)
    }

    fun stopEngine() {
        if (isLoaded) {
            nativeStop()
        }
    }
}
