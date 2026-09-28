package com.lpsm.vod.data

import com.lpsm.vod.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Compatibilidade com a implementação Xtream antiga.
 *
 * O LPSM 1.2 usa CatalogApi + backend M3U para o catálogo principal.
 * Este arquivo continua no repositório por compatibilidade com versões
 * anteriores e precisa acompanhar o novo modelo Episode(url).
 */
class XtreamApi(private val cfg: SourceConfig) {

    private fun get(action: String, extra: String = ""): String {
        val url =
            "${cfg.base}/player_api.php?username=${enc(cfg.username)}&password=${enc(cfg.password)}&action=$action$extra"

        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 25000
        c.setRequestProperty("User-Agent", "LPSM-VOD/1.2")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")

    fun movieCategories(): List<Category> = categories("get_vod_categories")
    fun seriesCategories(): List<Category> = categories("get_series_categories")

    private fun categories(action: String): List<Category> {
        val a = JSONArray(get(action))
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Category(
                o.optString("category_id"),
                o.optString("category_name", "Categoria")
            )
        }
    }

    fun movies(categoryId: String): List<PosterItem> {
        val a = JSONArray(
            get("get_vod_streams", "&category_id=${enc(categoryId)}")
        )

        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val id = o.optString("stream_id")
            val ext = o.optString("container_extension", "mp4")

            PosterItem(
                id = id,
                name = o.optString("name"),
                image = o.optString("stream_icon").takeIf { it.isNotBlank() },
                extension = ext,
                isSeries = false,
                url = "${cfg.base}/movie/${cfg.username}/${cfg.password}/$id.$ext"
            )
        }
    }

    fun series(categoryId: String): List<PosterItem> {
        val a = JSONArray(
            get("get_series", "&category_id=${enc(categoryId)}")
        )

        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)

            PosterItem(
                id = o.optString("series_id"),
                name = o.optString("name"),
                image = o.optString("cover").takeIf { it.isNotBlank() },
                extension = null,
                isSeries = true,
                url = null
            )
        }
    }

    fun seasons(seriesId: String): List<Season> {
        val root = JSONObject(
            get("get_series_info", "&series_id=${enc(seriesId)}")
        )

        val eps = root.optJSONObject("episodes") ?: return emptyList()
        val keys = eps.keys()
            .asSequence()
            .toList()
            .sortedBy { it.toIntOrNull() ?: 999 }

        return keys.mapNotNull { key ->
            val arr = eps.optJSONArray(key) ?: return@mapNotNull null

            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val info = o.optJSONObject("info")
                val id = o.optString("id")

                val ext =
                    info?.optString("container_extension")
                        ?.takeIf { it.isNotBlank() }
                        ?: o.optString("container_extension", "mp4")

                Episode(
                    id = id,
                    title = o.optString("title", "Episódio ${i + 1}"),
                    url = "${cfg.base}/series/${cfg.username}/${cfg.password}/$id.$ext"
                )
            }

            Season(
                number = key.toIntOrNull() ?: 0,
                episodes = list
            )
        }
    }

    fun movieUrl(item: PosterItem): String {
        return item.url
            ?: "${cfg.base}/movie/${cfg.username}/${cfg.password}/${item.id}.${item.extension ?: "mp4"}"
    }

    fun episodeUrl(ep: Episode): String = ep.url
}
