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
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import android.app.Activity
import android.content.ActivityNotFoundException
import android.print.PdfPrint
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
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
        // Serve os arquivos do app em https://appassets.androidplatform.net (origem https, necessária para o login/cache do Firebase).
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(r.url)

                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    if (r.url.host == WebViewAssetLoader.DEFAULT_DOMAIN) return false
                    startActivity(Intent(Intent.ACTION_VIEW, r.url)) // wa.me, tel:, etc.
                    return true
                }

                override fun onPageFinished(v: WebView, url: String) = checkUpdate()
            }
            addJavascriptInterface(Bridge(), "Android")
            loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
        }
        setContentView(web)
    }

    override fun onBackPressed() {
        web.evaluateJavascript("window.onBack && window.onBack()") { handled ->
            if (handled != "true") super.onBackPressed()
        }
    }

    private var lastCheck = 0L

    /** Ao voltar ao app depois de um tempo, procura atualização de novo. */
    override fun onResume() {
        super.onResume()
        if (lastCheck != 0L && System.currentTimeMillis() - lastCheck > 10 * 60 * 1000) checkUpdate()
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    private fun checkUpdate() {
        lastCheck = System.currentTimeMillis()
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

        /** Gera o PDF do documento aberto e envia ao WhatsApp (direto na conversa do número, se informado). */
        @JavascriptInterface fun sharePdf(name: String, phone: String, text: String) = runOnUiThread {
            val dir = File(cacheDir, "docs").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val file = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".pdf")
            PdfPrint.export(web.createPrintDocumentAdapter(name), file) { ok ->
                runOnUiThread {
                    if (!ok) {
                        Toast.makeText(this@MainActivity, "Não foi possível gerar o PDF.", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                    var d = phone.filter { it.isDigit() }
                    if (d.length in 10..11) d = "55$d"
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("application/pdf")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_TEXT, text)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (d.isNotEmpty()) send.putExtra("jid", "$d@s.whatsapp.net")
                    for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
                        try { startActivity(Intent(send).setPackage(pkg)); return@runOnUiThread } catch (_: ActivityNotFoundException) {}
                    }
                    startActivity(Intent.createChooser(send, null))
                }
            }
        }

        @JavascriptInterface fun openWhatsApp(phone: String, text: String) = runOnUiThread {
            var d = phone.filter { it.isDigit() }
            if (d.length in 10..11) d = "55$d"
            val u = Uri.parse("https://wa.me/$d?text=" + Uri.encode(text))
            startActivity(Intent(Intent.ACTION_VIEW, u))
        }
    }
}
