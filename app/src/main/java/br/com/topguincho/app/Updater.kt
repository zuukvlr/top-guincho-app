package br.com.topguincho.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Atualização pelo GitHub Releases: consulta a última release e baixa/instala o APK anexado. */
class Updater(private val ctx: Context) {

    data class Release(val version: String, val notes: String, val apkUrl: String)

    /** Retorna a release mais nova que a instalada, ou null. Chamar fora da thread principal. */
    fun check(): Release? {
        val c = open("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode != 200) return null
        val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val tag = json.optString("tag_name").removePrefix("v")
        val assets = json.optJSONArray("assets") ?: return null
        var apk = ""
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url")
        }
        if (apk.isEmpty() || !isNewer(tag, BuildConfig.VERSION_NAME)) return null
        return Release(tag, json.optString("body"), apk)
    }

    /** Baixa o APK reportando o progresso (0-100). Chamar fora da thread principal. */
    fun download(url: String, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "TopGuincho.apk")
        val c = open(url)
        val total = c.contentLengthLong
        var done = 0L
        c.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress((done * 100 / total).toInt())
                }
            }
        }
        return out
    }

    /** Abre o instalador do sistema. Se faltar a permissão de "fontes desconhecidas", leva o usuário até ela. */
    fun install(apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TopGuincho-App")
        }

    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
