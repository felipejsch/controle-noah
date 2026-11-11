package com.noah.controle

import android.annotation.SuppressLint
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.conscrypt.Conscrypt
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Gerencia a chave do app (criada uma vez e guardada em arquivo). */
class NoahAdbManager(dir: File) : AbsAdbConnectionManager() {
    private val priv: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        dir.mkdirs()
        val keyFile = File(dir, "adb_priv.der")
        val certFile = File(dir, "adb_cert.der")
        if (keyFile.exists() && certFile.exists()) {
            priv = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certFile.readBytes()))
        } else {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()
            val now = System.currentTimeMillis()
            val name = X500Name("CN=ControleDoNoah")
            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now),
                Date(now - 86_400_000L), Date(now + 3650L * 86_400_000L),
                name, kp.public
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
            val c = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            keyFile.writeBytes(kp.private.encoded)
            certFile.writeBytes(c.encoded)
            priv = kp.private
            cert = c
        }
    }

    override fun getPrivateKey(): PrivateKey = priv
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "ControleDoNoah"
}

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val executor = Executors.newSingleThreadExecutor()
    private var adb: NoahAdbManager? = null
    private var lastHost: String? = null
    private var lastPort: Int = 0

    private fun runKey(m: NoahAdbManager, keyCode: Int) {
        val stream = m.openStream("shell:input keyevent $keyCode")
        try { stream.openInputStream().readBytes() } catch (_: Throwable) {}
        try { stream.close() } catch (_: Throwable) {}
    }

    private fun keyDir() = File(filesDir, "adb_keys")

    private fun js(code: String) = runOnUiThread { webView.evaluateJavascript(code, null) }

    private fun errMsg(e: Throwable): String {
        val name = e.javaClass.simpleName
        return if (name.contains("Pairing")) "Falta parear: use o código da TV."
        else (e.message ?: name)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        try { Security.insertProviderAt(Conscrypt.newProvider(), 1) } catch (_: Throwable) {}

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()

        webView.addJavascriptInterface(TvBridge(), "AndroidTV")
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    private fun hideSystemUI() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    /** Descobre a porta de conexão atual da TV (ela muda) via mDNS. */
    private fun discoverConnectPort(host: String, timeoutMs: Long = 6000): Int? {
        val nsd = getSystemService(NSD_SERVICE) as NsdManager
        val latch = CountDownLatch(1)
        val found = java.util.concurrent.atomic.AtomicInteger(-1)

        val disc = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(s: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, code: Int) {}
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        if (si.host?.hostAddress == host) {
                            found.set(si.port)
                            latch.countDown()
                        }
                    }
                })
            }
            override fun onStartDiscoveryFailed(t: String, c: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(t: String, c: Int) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceLost(s: NsdServiceInfo) {}
        }
        nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, disc)
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        try { nsd.stopServiceDiscovery(disc) } catch (_: Exception) {}
        return found.get().takeIf { it >= 0 }
    }

    inner class TvBridge {

        @JavascriptInterface
        fun pair(host: String, port: Int, code: String) {
            executor.execute {
                try {
                    val m = NoahAdbManager(keyDir())
                    val r: Any? = m.pair(host.trim(), port, code.trim())
                    if (r == false) throw Exception("Código ou porta incorretos.")
                    try { m.close() } catch (_: Throwable) {}
                    js("window.onNativePairResult(true, 'Pareado!');")
                } catch (e: Throwable) {
                    js("window.onNativePairResult(false, ${JSONObject.quote(errMsg(e))});")
                }
            }
        }

        @JavascriptInterface
        fun connect(ipAndPort: String) {
            executor.execute {
                try {
                    try { adb?.close() } catch (_: Throwable) {}
                    adb = null

                    val parts = ipAndPort.trim().split(":")
                    val host = parts[0]
                    val port = parts.getOrNull(1)?.toIntOrNull()
                        ?: discoverConnectPort(host)
                        ?: throw Exception("Não achei a TV. Ligue a Depuração sem fio e confira o IP.")

                    val m = NoahAdbManager(keyDir())
                    val r: Any? = m.connect(host, port)
                    if (r == false) throw Exception("A TV recusou a conexão.")
                    adb = m
                    lastHost = host
                    lastPort = port

                    js("window.onNativeConnectResult(true, 'Conectado!', '$host');")
                } catch (e: Throwable) {
                    js("window.onNativeConnectResult(false, ${JSONObject.quote(errMsg(e))}, null);")
                }
            }
        }

                @JavascriptInterface
        fun sendKey(cmd: String) {
            val keyCode = when (cmd.uppercase()) {
                "UP" -> 19
                "DOWN" -> 20
                "LEFT" -> 21
                "RIGHT" -> 22
                "OK" -> 23
                "BACK" -> 4
                else -> return
            }
            executor.execute {
                val m = adb ?: return@execute
                try {
                    runKey(m, keyCode)
                } catch (e1: Throwable) {
                    try {
                        val h = lastHost ?: throw e1
                        try { m.close() } catch (_: Throwable) {}
                        val p = discoverConnectPort(h) ?: lastPort
                        val m2 = NoahAdbManager(keyDir())
                        val r: Any? = m2.connect(h, p)
                        if (r == false) throw e1
                        adb = m2
                        lastPort = p
                        runKey(m2, keyCode)
                    } catch (e2: Throwable) {
                        js("window.onNativeConnectionLost(${JSONObject.quote(errMsg(e2))});")
                    }
                }
            }
        }
