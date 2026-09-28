package com.lpsm.vod.data

import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

object DeviceApi {
    private const val DEFAULT_BACKEND = "https://lpsm-filmes-series-backend.onrender.com"
    private const val REMOTE_BACKEND_FILE = "https://raw.githubusercontent.com/tvonbox3-byte/lpsm-filmes-series/main/backend-url.txt"
    private const val BACKEND_CACHE_MS = 24L * 60L * 60L * 1000L
    private const val ACTIVATION_FALLBACK_MS = 7L * 24L * 60L * 60L * 1000L

    data class Activation(
        val active: Boolean,
        val message: String,
        val name: String = "",
        val expiresAt: String = "",
        val sourceUrl: String = ""
    )

    fun deviceCode(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "lpsm-vod-device"
        val digest = MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray())
        val raw = digest.take(6).joinToString("") { "%02X".format(it) }
        return raw.chunked(2).joinToString(":")
    }

    fun backendUrl(context: Context): String {
        val p = context.getSharedPreferences("backend", Context.MODE_PRIVATE)
        val saved = p.getString("url", null)
        val savedAt = p.getLong("savedAt", 0L)
        if (!saved.isNullOrBlank() && System.currentTimeMillis() - savedAt < BACKEND_CACHE_MS) return saved

        val remote = try {
            val c = URL(REMOTE_BACKEND_FILE).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 3000
                c.readTimeout = 3000
                c.useCaches = false
                c.setRequestProperty("User-Agent", "LPSM-VOD/1.5.0")
                val text = c.inputStream.bufferedReader().use { it.readText().trim() }.trimEnd('/')
                if (text.startsWith("https://") || text.startsWith("http://")) text else null
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) {
            null
        }

        val result = remote ?: saved ?: DEFAULT_BACKEND
        p.edit().putString("url", result).putLong("savedAt", System.currentTimeMillis()).apply()
        return result
    }

    fun cachedSourceUrl(context: Context): String =
        context.getSharedPreferences("activation", Context.MODE_PRIVATE)
            .getString("sourceUrl", "")
            .orEmpty()
            .trim()

    fun cachedActivation(context: Context): Activation? {
        val p = context.getSharedPreferences("activation", Context.MODE_PRIVATE)
        val at = p.getLong("savedAt", 0L)
        if (System.currentTimeMillis() - at > ACTIVATION_FALLBACK_MS) return null
        if (!p.getBoolean("active", false)) return null
        val source = p.getString("sourceUrl", "").orEmpty().trim()
        if (!source.startsWith("http://") && !source.startsWith("https://")) return null
        return Activation(
            active = true,
            message = "Usando ativação salva",
            name = p.getString("name", "").orEmpty(),
            expiresAt = p.getString("expiresAt", "").orEmpty(),
            sourceUrl = source
        )
    }

    private fun saveActive(context: Context, a: Activation) {
        if (!a.active || a.sourceUrl.isBlank()) return
        context.getSharedPreferences("activation", Context.MODE_PRIVATE).edit()
            .putBoolean("active", true)
            .putString("name", a.name)
            .putString("expiresAt", a.expiresAt)
            .putString("sourceUrl", a.sourceUrl.trim())
            .putLong("savedAt", System.currentTimeMillis())
            .apply()
    }

    private fun clearActive(context: Context) {
        context.getSharedPreferences("activation", Context.MODE_PRIVATE).edit()
            .putBoolean("active", false)
            .putLong("savedAt", System.currentTimeMillis())
            .apply()
    }

    fun fetchActivation(context: Context): Activation {
        try {
            val mac = deviceCode(context)
            val url = "${backendUrl(context)}/api/device/config?mac=${URLEncoder.encode(mac, "UTF-8")}" 
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                // Dá tempo para o Render Free acordar, sem bloquear o catálogo local.
                c.connectTimeout = 10_000
                c.readTimeout = 25_000
                c.useCaches = false
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("User-Agent", "LPSM-VOD/1.5.0")

                val body = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val root = JSONObject(body.ifBlank { "{}" })
                val active = root.optBoolean("active", false)
                val result = Activation(
                    active = active,
                    message = root.optString("message", if (active) "Ativado" else "Aguardando ativação no painel."),
                    name = root.optString("name"),
                    expiresAt = root.optString("expiresAt"),
                    sourceUrl = root.optString("sourceUrl")
                )

                if (result.active && result.sourceUrl.isNotBlank()) saveActive(context, result)
                else if (!result.active) clearActive(context)

                return result
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            return cachedActivation(context) ?: throw e
        }
    }

    fun heartbeat(context: Context) {
        try {
            val endpoint = URL("${backendUrl(context)}/api/device/presence")
            val c = endpoint.openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 5000
                c.readTimeout = 8000
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("User-Agent", "LPSM-VOD/1.5.0")
                val payload = JSONObject().put("mac", deviceCode(context)).toString().toByteArray()
                c.outputStream.use { it.write(payload) }
                (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.close()
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) {
            // Presença online nunca bloqueia o uso do catálogo que já está salvo.
        }
    }
}
