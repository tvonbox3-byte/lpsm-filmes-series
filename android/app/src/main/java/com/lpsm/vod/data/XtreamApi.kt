package com.lpsm.vod.data

import com.lpsm.vod.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class XtreamApi(private val cfg: SourceConfig) {
    private fun get(action: String, extra: String = ""): String {
        val url = "${cfg.base}/player_api.php?username=${enc(cfg.username)}&password=${enc(cfg.password)}&action=$action$extra"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 25000
        c.setRequestProperty("User-Agent", "LPSM-VOD/1.0")
        return c.inputStream.bufferedReader().use { it.readText() }
    }
    private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")

    fun movieCategories(): List<Category> = categories("get_vod_categories")
    fun seriesCategories(): List<Category> = categories("get_series_categories")

    private fun categories(action: String): List<Category> {
        val a = JSONArray(get(action))
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Category(o.optString("category_id"), o.optString("category_name", "Categoria"))
        }
    }

    fun movies(categoryId: String): List<PosterItem> {
        val a = JSONArray(get("get_vod_streams", "&category_id=${enc(categoryId)}"))
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            PosterItem(o.optString("stream_id"), o.optString("name"), o.optString("stream_icon").takeIf { it.isNotBlank() }, o.optString("container_extension", "mp4"), false)
        }
    }

    fun series(categoryId: String): List<PosterItem> {
        val a = JSONArray(get("get_series", "&category_id=${enc(categoryId)}"))
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            PosterItem(o.optString("series_id"), o.optString("name"), o.optString("cover").takeIf { it.isNotBlank() }, null, true)
        }
    }

    fun seasons(seriesId: String): List<Season> {
        val root = JSONObject(get("get_series_info", "&series_id=${enc(seriesId)}"))
        val eps = root.optJSONObject("episodes") ?: return emptyList()
        val keys = eps.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: 999 }
        return keys.mapNotNull { key ->
            val arr = eps.optJSONArray(key) ?: return@mapNotNull null
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val info = o.optJSONObject("info")
                Episode(o.optString("id"), o.optString("title", "Episódio ${i+1}"), info?.optString("container_extension") ?: o.optString("container_extension", "mp4"))
            }
            Season(key.toIntOrNull() ?: 0, list)
        }
    }

    fun movieUrl(item: PosterItem) = "${cfg.base}/movie/${cfg.username}/${cfg.password}/${item.id}.${item.extension ?: "mp4"}"
    fun episodeUrl(ep: Episode) = "${cfg.base}/series/${cfg.username}/${cfg.password}/${ep.id}.${ep.extension ?: "mp4"}"
}
