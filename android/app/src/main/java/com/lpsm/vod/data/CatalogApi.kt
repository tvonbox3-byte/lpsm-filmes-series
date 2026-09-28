package com.lpsm.vod.data

import android.content.Context
import com.lpsm.vod.model.Category
import com.lpsm.vod.model.Episode
import com.lpsm.vod.model.PosterItem
import com.lpsm.vod.model.Season
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class CatalogApi(private val context: Context) {
    private val mac get() = DeviceApi.deviceCode(context)

    private fun get(path: String): JSONObject {
        val url = URL("${DeviceApi.backendUrl(context)}$path")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 60000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "LPSM-VOD/1.2.1")
        val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        val root = JSONObject(raw.ifBlank { "{}" })
        if (!root.optBoolean("active", true)) {
            throw IllegalStateException(root.optString("message", "Aparelho não ativado"))
        }
        if (c.responseCode !in 200..299) throw IllegalStateException(root.optString("error", "Erro ${c.responseCode}"))
        return root
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun headers(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val result = linkedMapOf<String, String>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = o.optString(key)
            if (key.isNotBlank() && value.isNotBlank()) result[key] = value
        }
        return result
    }

    fun categories(series: Boolean): Pair<List<Category>, String> {
        val kind = if (series) "series" else "movie"
        val root = get("/api/device/catalog/categories?mac=${enc(mac)}&kind=$kind")
        val a = root.optJSONArray("categories") ?: JSONArray()
        val list = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Category(o.optString("id"), o.optString("name", "Categoria"))
        }
        val stats = root.optJSONObject("stats")
        val summary = if (series) {
            "${stats?.optInt("series", 0) ?: 0} séries • ${stats?.optInt("episodes", 0) ?: 0} episódios"
        } else {
            "${stats?.optInt("movies", 0) ?: 0} filmes"
        }
        return list to summary
    }

    fun items(series: Boolean, categoryId: String): List<PosterItem> {
        val kind = if (series) "series" else "movie"
        val root = get("/api/device/catalog/items?mac=${enc(mac)}&kind=$kind&categoryId=${enc(categoryId)}")
        val a = root.optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            PosterItem(
                id = o.optString("id"),
                name = o.optString("name"),
                image = o.optString("image").takeIf { it.isNotBlank() },
                isSeries = o.optBoolean("isSeries", series),
                url = o.optString("url").takeIf { it.isNotBlank() },
                headers = headers(o.optJSONObject("headers"))
            )
        }
    }

    fun seasons(seriesId: String): List<Season> {
        val root = get("/api/device/catalog/series?mac=${enc(mac)}&seriesId=${enc(seriesId)}")
        val seasons = root.optJSONArray("seasons") ?: JSONArray()
        return (0 until seasons.length()).map { i ->
            val s = seasons.getJSONObject(i)
            val eps = s.optJSONArray("episodes") ?: JSONArray()
            Season(
                number = s.optInt("number", i + 1),
                episodes = (0 until eps.length()).map { j ->
                    val e = eps.getJSONObject(j)
                    Episode(
                        id = e.optString("id"),
                        title = e.optString("title", "Episódio ${j + 1}"),
                        url = e.optString("url"),
                        headers = headers(e.optJSONObject("headers"))
                    )
                }
            )
        }
    }
}
