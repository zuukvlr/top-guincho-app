package br.com.topguincho.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.app.Activity
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var updater: Updater
    private var pending: Updater.Release? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updater = Updater(this)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    if (r.url.scheme == "file") return false
                    startActivity(Intent(Intent.ACTION_VIEW, r.url)) // wa.me, tel:, etc.
                    return true
                }

                override fun onPageFinished(v: WebView, url: String) = checkUpdate()
            }
            addJavascriptInterface(Bridge(), "Android")
            loadUrl("file:///android_asset/www/index.html")
        }
        setContentView(web)
    }

    override fun onBackPressed() {
        web.evaluateJavascript("window.onBack && window.onBack()") { handled ->
            if (handled != "true") super.onBackPressed()
        }
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    private fun checkUpdate() {
        thread {
            try {
                val r = updater.check() ?: return@thread
                pending = r
                js("window.onUpdate && onUpdate(${JSONObject().put("version", r.version).put("notes", r.notes)})")
            } catch (_: Exception) { /* sem internet: segue sem avisar */ }
        }
    }

    inner class Bridge {
        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface fun checkUpdate() = this@MainActivity.checkUpdate()

        @JavascriptInterface fun startUpdate() {
            val r = pending ?: return
            thread {
                try {
                    val apk = updater.download(r.apkUrl) { js("window.onUpdateProgress && onUpdateProgress($it)") }
                    js("window.onUpdateProgress && onUpdateProgress(100)")
                    if (!updater.install(apk)) js("window.onUpdateNeedsPermission && onUpdateNeedsPermission()")
                } catch (e: Exception) {
                    js("window.onUpdateError && onUpdateError()")
                }
            }
        }

        /** Abre a impressão do Android (permite "Salvar como PDF" e compartilhar). */
        @JavascriptInterface fun print(title: String) = runOnUiThread {
            val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
            pm.print(title, web.createPrintDocumentAdapter(title), PrintAttributes.Builder().build())
        }

        @JavascriptInterface fun shareText(text: String) = runOnUiThread {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null
                )
            )
        }

        @JavascriptInterface fun openWhatsApp(phone: String, text: String) = runOnUiThread {
            var d = phone.filter { it.isDigit() }
            if (d.length in 10..11) d = "55$d"
            val u = Uri.parse("https://wa.me/$d?text=" + Uri.encode(text))
            startActivity(Intent(Intent.ACTION_VIEW, u))
        }
    }
}
