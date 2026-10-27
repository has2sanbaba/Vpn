package org.greatfire.browser.vpn

import android.content.Context
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.util.Log
import org.greatfire.browser.R
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Provides genuine cryptographic verification of Let's Encrypt (ISRG Root X1 & X2) certificates
 * on older Android phones (Android 5.0 Lollipop - Android 7.0 Nougat, API 21..24) whose factory
 * ROM trust store predates ISRG Root X1, without ever weakening certificate validation!
 */
object LegacySslTrustBridge {
    private const val TAG = "LegacySslTrustBridge"

    @Volatile
    var trustManager: X509TrustManager? = null
        private set

    @Volatile
    var sslSocketFactory: SSLSocketFactory? = null
        private set

    fun initialize(context: Context) {
        try {
            val cf = CertificateFactory.getInstance("X.509")
            val isrgX1 = context.resources.openRawResource(R.raw.isrg_root_x1).use {
                cf.generateCertificate(it) as X509Certificate
            }
            val isrgX2 = context.resources.openRawResource(R.raw.isrg_root_x2).use {
                cf.generateCertificate(it) as X509Certificate
            }

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("isrg_root_x1", isrgX1)
                setCertificateEntry("isrg_root_x2", isrgX2)

                // Include all existing Android system CA certificates
                val systemKs = KeyStore.getInstance("AndroidCAStore")
                systemKs.load(null, null)
                val aliases = systemKs.aliases()
                while (aliases.hasMoreElements()) {
                    val alias = aliases.nextElement()
                    systemKs.getCertificate(alias)?.let { cert ->
                        setCertificateEntry("sys_$alias", cert)
                    }
                }
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
            Log.w(TAG, "Custom trust store init skipped: ${t.message}")
        }
    }

    /**
     * Used ONLY on legacy Android < 7.1.1 (API < 25) when WebView reports SSL_UNTRUSTED due to
     * missing ISRG Root X1. Returns true ONLY if the leaf certificate passes full cryptographic
     * trust validation AND RFC 2818 hostname verification.
     */
    fun verifyLegacyWebViewSslError(error: SslError, expectedHost: String?): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) return false
        if (error.primaryError != SslError.SSL_UNTRUSTED) return false
        val host = expectedHost?.takeIf { it.isNotBlank() } ?: return false
        val tm = trustManager ?: return false

        return try {
            val bundle = SslCertificate.saveState(error.certificate)
            val derBytes = bundle.getByteArray("x509-certificate") ?: return false
            val cf = CertificateFactory.getInstance("X.509")
            val x509 = cf.generateCertificate(ByteArrayInputStream(derBytes)) as X509Certificate
            x509.checkValidity()
            tm.checkServerTrusted(arrayOf(x509), "ECDHE_RSA")
            // Also verify subjectAltName / CN matches expectedHost!
            HttpsURLConnection.getDefaultHostnameVerifier().verify(host, FakeCertSession(x509))
        } catch (_: Throwable) {
            false
        }
    }

    private class FakeCertSession(private val leaf: X509Certificate) : javax.net.ssl.SSLSession {
        override fun getPeerCertificates() = arrayOf<java.security.cert.Certificate>(leaf)
        override fun getId() = ByteArray(0)
        override fun getSessionContext() = null
        override fun getCreationTime() = 0L
        override fun getLastAccessedTime() = 0L
        override fun invalidate() {}
        override fun isValid() = true
        override fun putValue(name: String?, value: Any?) {}
        override fun getValue(name: String?) = null
        override fun removeValue(name: String?) {}
        override fun getValueNames() = emptyArray<String>()
        override fun getLocalCertificates() = null
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getPeerCertificateChain() = emptyArray<javax.security.cert.X509Certificate>()
        override fun getPeerPrincipal() = leaf.subjectX500Principal
        override fun getLocalPrincipal() = null
        override fun getCipherSuite() = "TLS_AES_128_GCM_SHA256"
        override fun getProtocol() = "TLSv1.3"
        override fun getPeerHost() = ""
        override fun getPeerPort() = 443
        override fun getPacketBufferSize() = 16384
        override fun getApplicationBufferSize() = 16384
    }
}
