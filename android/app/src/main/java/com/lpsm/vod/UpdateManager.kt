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
    @Volatile private var downloading = false
    @Volatile private var lastCheckAt = 0L
    @Volatile private var attemptedVersion = -1L
    @Volatile private var pendingPermissionInfo: Info? = null

    data class Info(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val message: String
    )

    /**
     * Verifica e INICIA o download automaticamente quando houver versão nova.
     * O usuário não precisa entrar no GitHub nem baixar APK manualmente.
     * O Android ainda pode exigir confirmação da instalação.
     */
    fun check(activity: Activity, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckAt < 45_000L) return
        if (checking || downloading) return

        checking = true
        lastCheckAt = now

        pool.execute {
            try {
                // O JSON da release é mais leve e evita gastar limite da API do GitHub.
                val info = loadFromUpdateJson() ?: loadFromReleaseApi() ?: return@execute
                val installed = installedVersionCode(activity)

                if (info.versionCode > installed &&
                    info.apkUrl.startsWith("https://") &&
                    attemptedVersion != info.versionCode
                ) {
                    attemptedVersion = info.versionCode
                    activity.runOnUiThread {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            startAutomaticUpdate(activity, info)
                        }
                    }
                }
            } catch (_: Exception) {
                // Nunca bloqueia o app.
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

        // Ao voltar ao app, verifica de novo imediatamente.
        check(activity, force = true)
    }

    private fun startAutomaticUpdate(activity: Activity, info: Info) {
        if (Build.VERSION.SDK_INT >= 26 &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            pendingPermissionInfo = info

            AlertDialog.Builder(activity)
                .setTitle("Permitir atualização automática")
                .setMessage(
                    "Há uma nova versão ${info.versionName}. " +
                    "Ative “Permitir desta fonte” uma única vez. " +
                    "Depois, o LPSM baixa as próximas atualizações sozinho."
                )
                .setPositiveButton("PERMITIR") { _, _ ->
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        )
                    )
                }
                .setNegativeButton("DEPOIS") { _, _ ->
                    attemptedVersion = -1L
                    pendingPermissionInfo = null
                }
                .show()
            return
        }

        downloadAndInstall(activity, info)
    }

    private fun loadFromUpdateJson(): Info? {
        val root = readJson("$UPDATE_JSON?nocache=${System.currentTimeMillis()}") ?: return null
        val code = root.optLong("versionCode", 0L)
        val apk = root.optString("apkUrl")
        if (code <= 0L || apk.isBlank()) return null

        return Info(
            versionCode = code,
            versionName = root.optString("versionName", "nova"),
            apkUrl = apk,
            sha256 = root.optString("sha256"),
            message = root.optString("message", "Nova versão disponível.")
        )
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

    private fun readJson(url: String, githubApi: Boolean = false): JSONObject? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.instanceFollowRedirects = true
            c.useCaches = false
            c.connectTimeout = 15_000
            c.readTimeout = 25_000
            c.setRequestProperty("User-Agent", "LPSM-VOD-Updater/1.6.0")
            c.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
            c.setRequestProperty("Pragma", "no-cache")
            c.setRequestProperty(
                "Accept",
                if (githubApi) "application/vnd.github+json" else "application/json"
            )

            val code = c.responseCode
            if (code !in 200..299) return null
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun installedVersionCode(activity: Activity): Long {
        val pkg = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) {
            pkg.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkg.versionCode.toLong()
        }
    }

    private fun downloadAndInstall(activity: Activity, info: Info) {
        if (downloading) return
        downloading = true

        Toast.makeText(
            activity,
            "Atualização ${info.versionName} encontrada. Baixando automaticamente...",
            Toast.LENGTH_LONG
        ).show()

        pool.execute {
            try {
                val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                val apk = File(dir, "LPSM-Filmes-Series-${info.versionCode}.apk")
                if (apk.exists()) apk.delete()

                val sep = if (info.apkUrl.contains('?')) "&" else "?"
                val url = "${info.apkUrl}${sep}nocache=${System.currentTimeMillis()}"

                val c = URL(url).openConnection() as HttpURLConnection
                try {
                    c.instanceFollowRedirects = true
                    c.useCaches = false
                    c.connectTimeout = 20_000
                    c.readTimeout = 180_000
                    c.setRequestProperty("User-Agent", "LPSM-VOD-Updater/1.6.0")
                    c.setRequestProperty("Cache-Control", "no-cache")
                    c.setRequestProperty(
                        "Accept",
                        "application/vnd.android.package-archive,*/*"
                    )

                    val code = c.responseCode
                    if (code !in 200..299) {
                        throw IllegalStateException("Download retornou HTTP $code")
                    }

                    c.inputStream.use { input ->
                        apk.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } finally {
                    c.disconnect()
                }

                if (!apk.exists() || apk.length() < 100_000L) {
                    throw IllegalStateException("APK recebido está incompleto")
                }

                verifySha(apk, info.sha256)

                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        Toast.makeText(
                            activity,
                            "Download concluído. Confirme a atualização no Android.",
                            Toast.LENGTH_LONG
                        ).show()
                        install(activity, apk)
                    }
                }
            } catch (e: Exception) {
                attemptedVersion = -1L
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        "Não foi possível atualizar agora: ${e.message ?: "erro de download"}. O app tentará novamente.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                downloading = false
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
        if (!actual.equals(expected, ignoreCase = true)) {
            apk.delete()
            throw IllegalStateException("Arquivo de atualização inválido")
        }
    }

    private fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.files",
            apk
        )

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
            attemptedVersion = -1L
            Toast.makeText(
                activity,
                "Não foi possível abrir o instalador: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
