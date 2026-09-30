package com.lpsm.vod.data

import android.content.Context
import com.lpsm.vod.model.Category
import com.lpsm.vod.model.Episode
import com.lpsm.vod.model.PosterItem
import com.lpsm.vod.model.Season
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class CatalogApi(private val context: Context) {
    private val mac get() = DeviceApi.deviceCode(context)
    companion object {
        fun clearCatalogCache(context: Context) {
            listOf("vod_catalog_cache_v183", "vod_catalog_cache").forEach { name ->
                File(context.filesDir, name).deleteRecursively()
            }
        }
    }

    fun hasCachedCategories(series: Boolean): Boolean = false

    private fun get(path: String): JSONObject {
        try {
            val url = URL("${DeviceApi.backendUrl(context)}$path")
            val c = url.openConnection() as HttpURLConnection

            try {
                c.connectTimeout = 10000
                c.readTimeout = 65000
                c.useCaches = false
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("Cache-Control", "no-cache")
                c.setRequestProperty("User-Agent", "LPSM-VOD/1.8.5")

                val code = c.responseCode
                val raw = (if (code in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()

                val root = try {
                    JSONObject(raw.ifBlank { "{}" })
                } catch (_: Exception) {
                    JSONObject()
                }

                // Ativação inválida continua sendo tratada separadamente.
                if (!root.optBoolean("active", true)) {
                    val msg = root.optString("message")
                    throw IllegalStateException(msg.ifBlank { "Aparelho não ativado no painel" })
                }

                if (code !in 200..299) {
                    val serverMessage = root.optString("message")
                        .ifBlank { root.optString("error") }.trim()
                    throw IllegalStateException(serverMessage.ifBlank { "Servidor indisponível (HTTP $code)" })
                }

                // Um catálogo sem categorias de VOD é considerado login/fonte inválida.
                if (path.contains("/categories")) {
                    val categories = root.optJSONArray("categories")
                    if (categories == null || categories.length() == 0) {
                        throw IllegalStateException("Login não está funcionando")
                    }
                }

                if (path.contains("/api/device/catalog/series") && !hasEpisodes(root)) {
                    throw IllegalStateException("Servidor de episódios temporariamente indisponível")
                }
                return root
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            throw e
        }
    }

    private fun hasEpisodes(root: JSONObject): Boolean {
        val seasons = root.optJSONArray("seasons") ?: return false
        for (i in 0 until seasons.length()) {
            if ((seasons.optJSONObject(i)?.optJSONArray("episodes")?.length() ?: 0) > 0) return true
        }
        return false
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
            val seriesCount = stats?.optInt("series", 0) ?: 0
            val episodeCount = stats?.optInt("episodes", -1) ?: -1
            if (episodeCount > 0) {
                "$seriesCount séries • $episodeCount episódios"
            } else {
                "$seriesCount séries"
            }
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


    fun search(series: Boolean, query: String): List<PosterItem> {
        val kind = if (series) "series" else "movie"
        val root = get(
            "/api/device/catalog/search?mac=${enc(mac)}&kind=$kind&q=${enc(query)}"
        )
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
            val seasonNo = s.optInt("number", i + 1)
            val eps = s.optJSONArray("episodes") ?: JSONArray()
            Season(
                number = seasonNo,
                episodes = (0 until eps.length()).map { j ->
                    val e = eps.getJSONObject(j)
                    Episode(
                        id = e.optString("id"),
                        title = e.optString("title", "Episódio ${j + 1}"),
                        url = e.optString("url"),
                        headers = headers(e.optJSONObject("headers")),
                        number = e.optInt("number", j + 1),
                        season = seasonNo,
                        alternateUrl = e.optString("alternateUrl")
                    )
                }.sortedBy { it.number }
            )
        }.sortedBy { it.number }
    }

    fun prefetchHome() {
        try {
            val movieCats = categories(false).first
            movieCats.take(3).forEach { items(false, it.id) }
        } catch (_: Exception) { }
        try {
            val seriesCats = categories(true).first
            seriesCats.take(3).forEach { items(true, it.id) }
        } catch (_: Exception) { }
    }
}
