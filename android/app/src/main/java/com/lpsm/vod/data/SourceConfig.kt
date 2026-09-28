package com.lpsm.vod.data

import android.content.Context
import android.net.Uri

data class SourceConfig(
    val base: String,
    val username: String,
    val password: String,
    val clientName: String = "",
    val expiresAt: String = ""
) {
    companion object {
        fun fromUrl(raw: String): SourceConfig? {
            return try {
                val uri = Uri.parse(raw.trim())
                val user = uri.getQueryParameter("username") ?: return null
                val pass = uri.getQueryParameter("password") ?: return null
                val scheme = uri.scheme ?: "http"
                val authority = uri.authority ?: return null
                SourceConfig("$scheme://$authority", user, pass)
            } catch (_: Exception) { null }
        }

        fun load(ctx: Context): SourceConfig? {
            val p = ctx.getSharedPreferences("source", Context.MODE_PRIVATE)
            val base = p.getString("base", null) ?: return null
            val user = p.getString("user", null) ?: return null
            val pass = p.getString("pass", null) ?: return null
            return SourceConfig(
                base = base,
                username = user,
                password = pass,
                clientName = p.getString("clientName", "") ?: "",
                expiresAt = p.getString("expiresAt", "") ?: ""
            )
        }

        fun save(ctx: Context, cfg: SourceConfig) {
            ctx.getSharedPreferences("source", Context.MODE_PRIVATE).edit()
                .putString("base", cfg.base)
                .putString("user", cfg.username)
                .putString("pass", cfg.password)
                .putString("clientName", cfg.clientName)
                .putString("expiresAt", cfg.expiresAt)
                .apply()
        }

        fun clear(ctx: Context) {
            ctx.getSharedPreferences("source", Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
