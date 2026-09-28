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
    private const val PRIMARY_UPDATE_JSON =
        "https://github.com/tvonbox3-byte/lpsm-filmes-series/releases/download/auto-update/update.json"
    private const val FALLBACK_UPDATE_JSON =
        "https://github.com/tvonbox3-byte/lpsm-filmes-series/releases/latest/download/update.json"

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

    /**
     * Verifica atualização sem depender do painel/backend.
     * force=true ignora o intervalo mínimo entre verificações.
     */
    fun check(activity: Activity, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckAt < 30_000L) return
        if (checking) return

        checking = true
        lastCheckAt = now

        pool.execute {
            try {
                val info = loadInfo(PRIMARY_UPDATE_JSON)
                    ?: loadInfo(FALLBACK_UPDATE_JSON)
                    ?: return@execute

                val installed = installedVersionCode(activity)

                if (info.versionCode > installed &&
                    info.apkUrl.startsWith("https://") &&
                    promptedVersion != info.versionCode
                ) {
                    promptedVersion = info.versionCode
                    activity.runOnUiThread {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            show(activity, info)
                        }
                    }
                }
            } catch (_: Exception) {
                // A atualização nunca deve impedir o app de abrir.
            } finally {
                checking = false
            }
        }
    }

    /**
     * Chamado quando o app volta para a tela.
     * Se o usuário acabou de liberar "instalar apps desconhecidos",
     * continua a atualização automaticamente.
     */
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

    private fun loadInfo(baseUrl: String): Info? {
        val sep = if (baseUrl.contains("?")) "&" else "?"
        val url = "$baseUrl${sep}nocache=${System.currentTimeMillis()}"

        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.instanceFollowRedirects = true
            c.useCaches = false
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "LPSM-VOD-Updater/1.3.1")
            c.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
            c.setRequestProperty("Pragma", "no-cache")
            c.setRequestProperty("Accept", "application/json")

            val code = c.responseCode
            if (code !in 200..299) return null

            val body = c.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            val versionCode = json.optLong("versionCode", 0L)
            val apkUrl = json.optString("apkUrl")
            if (versionCode <= 0L || apkUrl.isBlank()) return null

            Info(
                versionCode = versionCode,
                versionName = json.optString("versionName", "nova"),
                apkUrl = apkUrl,
                sha256 = json.optString("sha256"),
                message = json.optString(
                    "message",
                    "Nova versão disponível."
                )
            )
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

    private fun show(activity: Activity, info: Info) {
        AlertDialog.Builder(activity)
            .setTitle("Atualização disponível")
            .setMessage(
                "Versão ${info.versionName}\n\n${info.message}\n\n" +
                    "O LPSM baixa o APK sozinho. Você só confirma a instalação no Android."
            )
            .setPositiveButton("ATUALIZAR") { _, _ ->
                prepareInstall(activity, info)
            }
            .setNegativeButton("DEPOIS") { _, _ ->
                // Permite avisar novamente na próxima abertura.
                promptedVersion = -1L
            }
            .show()
    }

    private fun prepareInstall(activity: Activity, info: Info) {
        if (Build.VERSION.SDK_INT >= 26 &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            pendingPermissionInfo = info
            Toast.makeText(
                activity,
                "Ative 'Permitir desta fonte' para o LPSM. Ao voltar, a atualização continua sozinha.",
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
        Toast.makeText(
            activity,
            "Baixando atualização...",
            Toast.LENGTH_SHORT
        ).show()

        pool.execute {
            try {
                val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                val apk = File(dir, "LPSM-Filmes-Series-${info.versionCode}.apk")
                if (apk.exists()) apk.delete()

                val sep = if (info.apkUrl.contains("?")) "&" else "?"
                val downloadUrl =
                    "${info.apkUrl}${sep}nocache=${System.currentTimeMillis()}"

                val c = URL(downloadUrl).openConnection() as HttpURLConnection
                try {
                    c.instanceFollowRedirects = true
                    c.useCaches = false
                    c.connectTimeout = 15_000
                    c.readTimeout = 120_000
                    c.setRequestProperty("User-Agent", "LPSM-VOD-Updater/1.3.1")
                    c.setRequestProperty("Cache-Control", "no-cache")
                    c.setRequestProperty("Accept", "application/vnd.android.package-archive,*/*")

                    val code = c.responseCode
                    if (code !in 200..299) {
                        throw IllegalStateException("Servidor retornou HTTP $code")
                    }

                    c.inputStream.use { input ->
                        apk.outputStream().use { out ->
                            input.copyTo(out)
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
                        install(activity, apk)
                    }
                }
            } catch (e: Exception) {
                promptedVersion = -1L
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        "Falha na atualização: ${e.message ?: "erro de download"}",
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
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.files",
            apk
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            promptedVersion = -1L
            Toast.makeText(
                activity,
                "Não foi possível abrir o instalador: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
