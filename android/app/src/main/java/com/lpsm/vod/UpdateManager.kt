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
    private const val RELEASE_API =
        "https://api.github.com/repos/tvonbox3-byte/lpsm-filmes-series/releases/tags/auto-update"
    private const val UPDATE_JSON =
        "https://github.com/tvonbox3-byte/lpsm-filmes-series/releases/download/auto-update/update.json"

    private val pool = Executors.newSingleThreadExecutor()

    @Volatile private var checking = false
    @Volatile private var lastCheckAt = 0L
    @Volatile private var promptedVersion = -1L
    @Volatile private var pendingPermissionInfo: Info? = null

    data class Info(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val message: String
    )

    fun check(activity: Activity, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckAt < 30_000L) return
        if (checking) return

        checking = true
        lastCheckAt = now

        pool.execute {
            try {
                val info = loadFromReleaseApi() ?: loadFromUpdateJson() ?: return@execute
                val installed = installedVersionCode(activity)

                if (info.versionCode > installed &&
                    info.apkUrl.startsWith("https://") &&
                    promptedVersion != info.versionCode
                ) {
                    promptedVersion = info.versionCode
                    activity.runOnUiThread {
                        if (!activity.isFinishing && !activity.isDestroyed) show(activity, info)
                    }
                }
            } catch (_: Exception) {
                // Atualização nunca impede o app de abrir.
            } finally {
                checking = false
            }
        }
    }

    fun onResume(activity: Activity) {
        val pending = pendingPermissionInfo
        if (pending != null &&
            (Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls())
        ) {
            pendingPermissionInfo = null
            downloadAndInstall(activity, pending)
            return
        }
        check(activity)
    }

    private fun loadFromReleaseApi(): Info? {
        val root = readJson(RELEASE_API, githubApi = true) ?: return null
        val body = root.optString("body")
        val meta = body.lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            .toMap()

        val versionCode = meta["versionCode"]?.toLongOrNull() ?: return null
        val versionName = meta["versionName"].orEmpty().ifBlank { "nova" }
        val sha = meta["sha256"].orEmpty()
        val assets = root.optJSONArray("assets") ?: return null
        var apkUrl = ""
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name") == "LPSM-Filmes-Series.apk") {
                apkUrl = a.optString("browser_download_url")
                break
            }
        }
        if (apkUrl.isBlank()) return null

        return Info(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            sha256 = sha,
            message = "Nova versão disponível."
        )
    }

    private fun loadFromUpdateJson(): Info? {
        val root = readJson("$UPDATE_JSON?nocache=${System.currentTimeMillis()}") ?: return null
        val code = root.optLong("versionCode", 0L)
        val apk = root.optString("apkUrl")
        if (code <= 0 || apk.isBlank()) return null
        return Info(
            versionCode = code,
            versionName = root.optString("versionName", "nova"),
            apkUrl = apk,
            sha256 = root.optString("sha256"),
            message = root.optString("message", "Nova versão disponível.")
        )
    }

    private fun readJson(url: String, githubApi: Boolean = false): JSONObject? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.instanceFollowRedirects = true
            c.useCaches = false
            c.connectTimeout = 12_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "LPSM-VOD-Updater/1.5.0")
            c.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
            c.setRequestProperty("Pragma", "no-cache")
            c.setRequestProperty("Accept", if (githubApi) "application/vnd.github+json" else "application/json")
            val code = c.responseCode
            if (code !in 200..299) return null
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun installedVersionCode(activity: Activity): Long {
        val pkg = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else {
            @Suppress("DEPRECATION")
            pkg.versionCode.toLong()
        }
    }

    private fun show(activity: Activity, info: Info) {
        AlertDialog.Builder(activity)
            .setTitle("Atualização disponível")
            .setMessage(
                "Versão ${info.versionName}\n\n" +
                    "O LPSM vai baixar a atualização. Depois, confirme Atualizar/Instalar no Android."
            )
            .setPositiveButton("ATUALIZAR") { _, _ -> prepareInstall(activity, info) }
            .setNegativeButton("DEPOIS") { _, _ -> promptedVersion = -1L }
            .show()
    }

    private fun prepareInstall(activity: Activity, info: Info) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            pendingPermissionInfo = info
            Toast.makeText(
                activity,
                "Ative 'Permitir desta fonte' para o LPSM. Ao voltar, o download continua.",
                Toast.LENGTH_LONG
            ).show()
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            return
        }
        downloadAndInstall(activity, info)
    }

    private fun downloadAndInstall(activity: Activity, info: Info) {
        Toast.makeText(activity, "Baixando atualização...", Toast.LENGTH_SHORT).show()

        pool.execute {
            try {
                val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                val apk = File(dir, "LPSM-Filmes-Series-${info.versionCode}.apk")
                if (apk.exists()) apk.delete()

                val sep = if (info.apkUrl.contains('?')) "&" else "?"
                val c = URL("${info.apkUrl}${sep}nocache=${System.currentTimeMillis()}")
                    .openConnection() as HttpURLConnection
                try {
                    c.instanceFollowRedirects = true
                    c.useCaches = false
                    c.connectTimeout = 20_000
                    c.readTimeout = 180_000
                    c.setRequestProperty("User-Agent", "LPSM-VOD-Updater/1.5.0")
                    c.setRequestProperty("Cache-Control", "no-cache")
                    c.setRequestProperty("Accept", "application/vnd.android.package-archive,*/*")
                    val code = c.responseCode
                    if (code !in 200..299) throw IllegalStateException("Download retornou HTTP $code")
                    c.inputStream.use { input -> apk.outputStream().use { output -> input.copyTo(output) } }
                } finally {
                    c.disconnect()
                }

                if (!apk.exists() || apk.length() < 100_000L) {
                    throw IllegalStateException("APK recebido está incompleto")
                }
                verifySha(apk, info.sha256)

                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) install(activity, apk)
                }
            } catch (e: Exception) {
                promptedVersion = -1L
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        "Falha ao atualizar: ${e.message ?: "erro de download"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun verifySha(apk: File, expected: String) {
        if (expected.isBlank()) return
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
        if (!actual.equals(expected, true)) {
            apk.delete()
            throw IllegalStateException("Arquivo de atualização inválido")
        }
    }

    private fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            promptedVersion = -1L
            Toast.makeText(activity, "Não foi possível abrir o instalador: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
