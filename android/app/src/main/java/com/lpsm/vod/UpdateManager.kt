package com.lpsm.vod

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

object UpdateManager {
    private const val UPDATE_JSON = "https://github.com/tvonbox3-byte/lpsm-filmes-series/releases/latest/download/update.json"
    private val pool = Executors.newSingleThreadExecutor()
    @Volatile private var checking = false

    data class Info(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val message: String
    )

    fun check(activity: Activity) {
        if (checking) return
        checking = true
        pool.execute {
            try {
                val c = URL(UPDATE_JSON).openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 8000
                c.setRequestProperty("User-Agent", "LPSM-VOD-Updater/1.1")
                val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val info = Info(
                    json.optLong("versionCode"),
                    json.optString("versionName"),
                    json.optString("apkUrl"),
                    json.optString("sha256"),
                    json.optString("message", "Nova versão disponível.")
                )
                val pkg = activity.packageManager.getPackageInfo(activity.packageName, 0)
                val installed = if (Build.VERSION.SDK_INT >= 28) {
                    pkg.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pkg.versionCode.toLong()
                }
                if (info.versionCode > installed && info.apkUrl.startsWith("https://")) {
                    activity.runOnUiThread { show(activity, info) }
                }
            } catch (_: Exception) { }
            finally { checking = false }
        }
    }

    private fun show(activity: Activity, info: Info) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle("Atualização disponível")
            .setMessage("Versão ${info.versionName}\n\n${info.message}")
            .setPositiveButton("ATUALIZAR") { _, _ -> prepareInstall(activity, info) }
            .setNegativeButton("DEPOIS", null)
            .show()
    }

    private fun prepareInstall(activity: Activity, info: Info) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Autorize o LPSM a instalar atualizações e volte ao aplicativo.", Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        Toast.makeText(activity, "Baixando atualização...", Toast.LENGTH_SHORT).show()
        pool.execute {
            try {
                val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                val apk = File(dir, "LPSM-Filmes-Series.apk")
                val c = URL(info.apkUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 60000
                c.setRequestProperty("User-Agent", "LPSM-VOD-Updater/1.1")
                c.inputStream.use { input -> apk.outputStream().use { out -> input.copyTo(out) } }

                if (info.sha256.isNotBlank()) {
                    val md = MessageDigest.getInstance("SHA-256")
                    apk.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            md.update(buffer, 0, n)
                        }
                    }
                    val actual = md.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(info.sha256, true)) throw IllegalStateException("Arquivo de atualização inválido")
                }

                activity.runOnUiThread { install(activity, apk) }
            } catch (e: Exception) {
                activity.runOnUiThread { Toast.makeText(activity, "Falha na atualização: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
