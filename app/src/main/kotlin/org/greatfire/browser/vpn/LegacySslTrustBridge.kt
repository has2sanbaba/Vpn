package org.greatfire.browser.vpn

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import org.greatfire.browser.R
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Exports a verified PEM CA bundle (`cacert.pem`) combining AndroidCAStore + ISRG Root X1/X2
 * so our in-process Go engine (`libgfproxy.so`) and legacy Android 5.0–7.0 OkHttp clients
 * perform genuine cryptographic X.509 validation across Android 5.0 through Android 16+.
 */
object LegacySslTrustBridge {
    private const val TAG = "LegacySslTrustBridge"

    @Volatile
    var trustManager: X509TrustManager? = null
        private set

    @Volatile
    var sslSocketFactory: SSLSocketFactory? = null
        private set

    @Volatile
    var caBundlePemPath: String? = null
        private set

    fun initialize(context: Context) {
        try {
            val pemFile = File(context.filesDir, "cacert.pem")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
                pemFile.exists() && pemFile.length() >= 8192L
            ) {
                caBundlePemPath = pemFile.absolutePath
                return
            }

            val cf = CertificateFactory.getInstance("X.509")
            val isrgX1 = context.resources.openRawResource(R.raw.isrg_root_x1).use {
                cf.generateCertificate(it) as X509Certificate
            }
            val isrgX2 = context.resources.openRawResource(R.raw.isrg_root_x2).use {
                cf.generateCertificate(it) as X509Certificate
            }

            val pemBuilder = StringBuilder(196 * 1024)
            appendPemCert(pemBuilder, isrgX1.encoded)
            appendPemCert(pemBuilder, isrgX2.encoded)

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("isrg_root_x1", isrgX1)
                setCertificateEntry("isrg_root_x2", isrgX2)

                val systemKs = KeyStore.getInstance("AndroidCAStore")
                systemKs.load(null, null)
                val aliases = systemKs.aliases()
                while (aliases.hasMoreElements()) {
                    val alias = aliases.nextElement()
                    systemKs.getCertificate(alias)?.let { cert ->
                        setCertificateEntry("sys_$alias", cert)
                        runCatching { appendPemCert(pemBuilder, cert.encoded) }
                    }
                }
            }

            if (!pemFile.exists() || pemFile.length() < 8192L) {
                runCatching {
                    pemFile.writeText(pemBuilder.toString(), Charsets.US_ASCII)
                }
            }
            if (pemFile.exists() && pemFile.length() > 0L) {
                caBundlePemPath = pemFile.absolutePath
            }

            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(keyStore)
            val x509Tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            if (x509Tm != null) {
                val sslCtx = SSLContext.getInstance("TLS")
                sslCtx.init(null, arrayOf(x509Tm), null)
                trustManager = x509Tm
                sslSocketFactory = sslCtx.socketFactory
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Custom trust store init warning: ${t.message}")
        }
    }

    fun ensureCaBundlePath(context: Context): String? {
        caBundlePemPath?.let { path ->
            if (File(path).exists()) return path
        }
        initialize(context.applicationContext)
        return caBundlePemPath
    }

    private fun appendPemCert(sb: StringBuilder, derBytes: ByteArray) {
        val b64 = Base64.encodeToString(derBytes, Base64.NO_WRAP)
        sb.append("-----BEGIN CERTIFICATE-----\n")
        var i = 0
        while (i < b64.length) {
            val end = minOf(i + 64, b64.length)
            sb.append(b64, i, end).append('\n')
            i = end
        }
        sb.append("-----END CERTIFICATE-----\n")
    }
}
