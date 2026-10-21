package com.example.myapp

import android.content.Context
import android.os.Build
import android.util.Log
import org.conscrypt.Conscrypt
import java.security.KeyStore
import java.security.Security
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 老机型要面对两个独立的问题：
 *
 * 1. TLS 版本：Android 4.x 默认只协商 TLS 1.0/1.1，现代服务端一律拒绝 → 用 Conscrypt 补 TLS 1.2。
 * 2. 根证书：2013 年前后的系统证书库没有 2015 年后才出现的根 CA（比如 Let's Encrypt 的 ISRG Root X1），
 *    表现为 "Trust anchor for certification path not found" → 内置一份 Mozilla CA 包，与系统证书库合并使用。
 */
object Tls {

    @Volatile
    private var socketFactory: SSLSocketFactory? = null

    @Volatile
    private var trustManager: X509TrustManager? = null

    @Volatile
    private var bundledCount: Int = 0

    @Volatile
    var providerName: String = "未初始化"
        private set

    private var app: Context? = null

    private const val TAG = "OldAI"
    private const val KEYSTORE_TYPE = "BKS"

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** 首次联网前调用；内部会解析 100 多个根证书，所以放在后台线程 */
    @Synchronized
    fun install() {
        if (socketFactory != null) return
        val context = app ?: throw IllegalStateException("Tls.init() 未调用")

        val managers = ArrayList<X509TrustManager>()
        bundledTrustManager(context)?.let {
            managers.add(it)
            bundledCount = it.acceptedIssuers.size
        }
        systemTrustManager()?.let { managers.add(it) }

        val composite = CompositeTrustManager(managers)

        var providerTag = "platform"
        val provider = if (Build.VERSION.SDK_INT < 21) {
            try {
                Conscrypt.newProvider().also {
                    Security.insertProviderAt(it, 1)
                    providerTag = "Conscrypt"
                }
            } catch (t: Throwable) {
                providerTag = "platform(no conscrypt)"
                null
            }
        } else null

        val ctx = if (provider != null) SSLContext.getInstance("TLS", provider) else SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(composite), null)

        socketFactory = ctx.socketFactory
        trustManager = composite
        providerName = "$providerTag + 内置CA($bundledCount) + 系统"
    }

    private fun bundledTrustManager(context: Context): X509TrustManager? = try {
        val cf = CertificateFactory.getInstance("X.509")
        val ks = KeyStore.getInstance(KEYSTORE_TYPE)
        ks.load(null, null)

        var count = 0
        val pem = StringBuilder()
        context.assets.open("cacert.pem").bufferedReader().useLines { lines ->
            for (line in lines) {
                val l = line.trim()
                if (l.startsWith("-----BEGIN CERTIFICATE-----")) {
                    pem.setLength(0)
                    pem.append(l).append('\n')
                } else if (l.startsWith("-----END CERTIFICATE-----")) {
                    pem.append(l).append('\n')
                    try {
                        // 逐个证书块解析，比 generateCertificates 在 4.x 上稳
                        val cert = cf.generateCertificate(pem.toString().byteInputStream()) as X509Certificate
                        ks.setCertificateEntry("ca" + count, cert)
                        count++
                    } catch (e: Throwable) {
                        Log.e(TAG, "parse cert #$count failed", e)
                    }
                } else if (pem.isNotEmpty()) {
                    pem.append(l).append('\n')
                }
            }
        }
        Log.i(TAG, "bundled certs loaded = $count")

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    } catch (t: Throwable) {
        Log.e(TAG, "bundled trust store failed", t)
        null
    }

    private fun systemTrustManager(): X509TrustManager? = try {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    } catch (t: Throwable) {
        null
    }

    fun socketFactory(): SSLSocketFactory = socketFactory ?: throw IllegalStateException("Tls not installed")
    fun trustManager(): X509TrustManager = trustManager ?: throw IllegalStateException("Tls not installed")

    /** 任一信任库认可即可，等价于「系统根证书 ∪ 内置根证书」 */
    private class CompositeTrustManager(
        private val delegates: List<X509TrustManager>
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            var last: CertificateException? = null
            for (d in delegates) {
                try {
                    d.checkClientTrusted(chain, authType)
                    return
                } catch (e: CertificateException) {
                    last = e
                }
            }
            throw last ?: CertificateException("no trust manager accepted the client chain")
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            var last: CertificateException? = null
            for (d in delegates) {
                try {
                    d.checkServerTrusted(chain, authType)
                    return
                } catch (e: CertificateException) {
                    last = e
                }
            }
            throw last ?: CertificateException("no trust manager accepted the server chain")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> {
            val all = ArrayList<X509Certificate>()
            for (d in delegates) all.addAll(d.acceptedIssuers)
            return all.toTypedArray()
        }
    }
}


