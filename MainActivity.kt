package com.noah.controle

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var dadbConnection: Dadb? = null
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var keyPair: AdbKeyPair

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mantém a tela sempre acesa e em modo imersivo
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        // Prepara chave criptográfica persistente para a TV lembrar do celular após o 1º pareamento
        val privateKeyFile = File(filesDir, "adbkey")
        val publicKeyFile = File(filesDir, "adbkey.pub")
        if (!privateKeyFile.exists() || !publicKeyFile.exists()) {
            AdbKeyPair.generate(privateKeyFile, publicKeyFile)
        }
        keyPair = AdbKeyPair.read(privateKeyFile, publicKeyFile)

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

    inner class TvBridge {

        @JavascriptInterface
        fun connect(ipAndPort: String) {
            executor.execute {
                try {
                    dadbConnection?.close()
                    val parts = ipAndPort.trim().split(":")
                    val host = parts[0]
                    val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 5555 else 5555

                    val conn = Dadb.create(host, port, keyPair)
                    // Testa a conexão executando um comando rápido
                    conn.shell("echo ok")
                    dadbConnection = conn

                    runOnUiThread {
                        webView.evaluateJavascript("window.onNativeConnectResult(true, 'Conectado!');", null)
                    }
                } catch (e: Exception) {
                    val msg = (e.message ?: "Erro ao conectar").replace("'", "")
                    runOnUiThread {
                        webView.evaluateJavascript("window.onNativeConnectResult(false, '$msg');", null)
                    }
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
                try {
                    dadbConnection?.shell("input keyevent $keyCode")
                } catch (e: Exception) {
                    // Tenta reconectar silenciosamente se cair
                }
            }
        }
    }
}
