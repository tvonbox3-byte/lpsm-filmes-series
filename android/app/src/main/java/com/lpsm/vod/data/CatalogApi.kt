package com.lpsm.vod.data

import android.content.Context
import com.lpsm.vod.model.Category
import com.lpsm.vod.model.Episode
import com.lpsm.vod.model.PosterItem
import com.lpsm.vod.model.Season
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/**
 * Catálogo VOD local.
 *
 * A partir da 1.5 o backend serve para ativação/configuração, mas a lista M3U é
 * baixada diretamente pela box e salva localmente. Isso evita que um cold-start
 * do Render precise processar uma lista grande antes de mostrar Filmes/Séries.
 */
class CatalogApi(private val context: Context) {
    private val cacheFile by lazy { File(context.filesDir, "vod_catalog_local_v150.json") }
    private val cacheTtlMs = 6L * 60L * 60L * 1000L
    @Volatile private var sourceUrl: String = DeviceApi.cachedSourceUrl(context)

    private data class Cached(val catalog: ParsedCatalog, val savedAt: Long)

    fun setSource(url: String) {
        val clean = url.trim()
        if (clean.isBlank()) return
        if (clean != sourceUrl) {
            sourceUrl = clean
        }
    }

    fun hasSavedCatalog(): Boolean = try {
        cacheFile.exists() && cacheFile.length() > 100
    } catch (_: Exception) {
        false
    }

    fun categories(series: Boolean): Pair<List<Category>, String> {
        val c = ensureCatalog()
        val list = if (series) c.seriesCategories else c.movieCategories
        val summary = if (series) {
            "${c.seriesCount} séries • ${c.episodeCount} episódios"
        } else {
            "${c.movieCount} filmes"
        }
        return list to summary
    }

    fun items(series: Boolean, categoryId: String): List<PosterItem> {
        val c = ensureCatalog()
        return if (series) {
            c.seriesByCategory[categoryId].orEmpty()
        } else {
            c.moviesByCategory[categoryId].orEmpty()
        }
    }

    fun seasons(seriesId: String): List<Season> =
        ensureCatalog().seriesById[seriesId]?.seasons.orEmpty()

    /**
     * A tela pode chamar depois que o catálogo já apareceu. Se estiver antigo,
     * atualizamos em segundo plano sem apagar a cópia que funciona.
     */
    fun refreshIfStale() {
        val source = currentSourceOrNull() ?: return
        val cached = loadCache(source) ?: return
        if (System.currentTimeMillis() - cached.savedAt <= cacheTtlMs) return
        refreshAsync(source)
    }

    fun forceRefreshAsync() {
        val source = currentSourceOrNull() ?: return
        refreshAsync(source)
    }

    fun prefetchHome() {
        // Na 1.5 o catálogo completo já fica local; aqui apenas atualizamos em segundo plano se necessário.
        refreshIfStale()
    }

    private fun ensureCatalog(): ParsedCatalog {
        val source = currentSource()
        val sourceHash = hash(source)

        sharedCatalog?.let {
            if (sharedSourceHash == sourceHash) return it
        }

        synchronized(LOCK) {
            sharedCatalog?.let {
                if (sharedSourceHash == sourceHash) return it
            }

            val cached = loadCache(source)
            if (cached != null) {
                sharedSourceHash = sourceHash
                sharedCatalog = cached.catalog
                if (System.currentTimeMillis() - cached.savedAt > cacheTtlMs) {
                    refreshAsync(source)
                }
                return cached.catalog
            }

            // Só na primeira utilização desta fonte precisamos aguardar a M3U.
            // Depois disso o app sempre abre do arquivo local primeiro.
            val parsed = downloadAndParse(source)
            saveCache(source, parsed)
            sharedSourceHash = sourceHash
            sharedCatalog = parsed
            return parsed
        }
    }

    private fun refreshAsync(source: String) {
        if (!REFRESHING.compareAndSet(false, true)) return
        REFRESH_POOL.execute {
            try {
                val parsed = downloadAndParse(source)
                synchronized(LOCK) {
                    saveCache(source, parsed)
                    if (hash(source) == hash(currentSourceOrNull().orEmpty())) {
                        sharedSourceHash = hash(source)
                        sharedCatalog = parsed
                    }
                }
            } catch (_: Exception) {
                // Nunca apaga o catálogo anterior por causa de uma falha de atualização.
            } finally {
                REFRESHING.set(false)
            }
        }
    }

    private fun currentSource(): String = currentSourceOrNull()
        ?: throw IllegalStateException("Lista M3U ainda não configurada para este aparelho.")

    private fun currentSourceOrNull(): String? {
        val local = sourceUrl.trim()
        if (local.startsWith("http://") || local.startsWith("https://")) return local
        val cached = DeviceApi.cachedSourceUrl(context).trim()
        if (cached.startsWith("http://") || cached.startsWith("https://")) {
            sourceUrl = cached
            return cached
        }
        return null
    }

    private fun downloadAndParse(sourceSpec: String): ParsedCatalog {
        val (urlText, requestHeaders) = splitSourceSpec(sourceSpec)
        val connection = URL(urlText).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.connectTimeout = 15_000
            // Listas grandes não são mais abortadas em 60 segundos.
            connection.readTimeout = 300_000
            connection.setRequestProperty("Accept", "application/x-mpegURL,text/plain,*/*")
            connection.setRequestProperty("User-Agent", requestHeaders["User-Agent"] ?: "LPSM-VOD/1.5.1")
            for ((key, value) in requestHeaders) {
                if (!key.equals("User-Agent", true)) connection.setRequestProperty(key, value)
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText().take(250) }.orEmpty()
                throw IllegalStateException("Lista M3U respondeu HTTP $code${if (error.isBlank()) "" else ": $error"}")
            }

            val input = decodedStream(connection)
            java.io.InputStreamReader(input, Charsets.UTF_8).buffered(64 * 1024).use { reader ->
                return M3uParser.parse(reader)
            }
        } catch (e: java.net.SocketTimeoutException) {
            throw IllegalStateException("Tempo esgotado ao baixar a lista M3U. A última lista salva será mantida.", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun decodedStream(c: HttpURLConnection): InputStream {
        val input = c.inputStream
        return if (c.contentEncoding?.contains("gzip", ignoreCase = true) == true) {
            GZIPInputStream(input)
        } else {
            input
        }
    }

    private fun splitSourceSpec(raw: String): Pair<String, Map<String, String>> {
        val pipe = raw.indexOf('|')
        if (pipe < 0) return raw.trim() to emptyMap()
        val headers = linkedMapOf<String, String>()
        for (part in raw.substring(pipe + 1).split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val key = part.substring(0, eq).trim().let { if (it.equals("referrer", true)) "Referer" else it }
            val value = try {
                URLDecoder.decode(part.substring(eq + 1).replace("+", "%20"), "UTF-8")
            } catch (_: Exception) {
                part.substring(eq + 1)
            }
            if (key.isNotBlank() && value.isNotBlank()) headers[key] = value
        }
        return raw.substring(0, pipe).trim() to headers
    }

    private fun saveCache(source: String, catalog: ParsedCatalog) {
        try {
            val root = JSONObject()
                .put("format", 2)
                .put("sourceHash", hash(source))
                .put("savedAt", System.currentTimeMillis())
                .put("movieCategories", categoriesToJson(catalog.movieCategories))
                .put("seriesCategories", categoriesToJson(catalog.seriesCategories))
                .put("moviesByCategory", itemsMapToJson(catalog.moviesByCategory))
                .put("seriesByCategory", itemsMapToJson(catalog.seriesByCategory))
                .put("seriesById", seriesMapToJson(catalog.seriesById))
                .put("ignored", catalog.ignored)

            val tmp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            tmp.writeText(root.toString())
            if (cacheFile.exists()) cacheFile.delete()
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeText(root.toString())
                tmp.delete()
            }
        } catch (_: Exception) {
            // Se não for possível gravar, o catálogo em memória continua funcionando.
        }
    }

    private fun loadCache(source: String): Cached? {
        if (!cacheFile.exists() || cacheFile.length() < 100) return null
        return try {
            val root = JSONObject(cacheFile.readText())
            if (root.optInt("format") != 2) return null
            if (root.optString("sourceHash") != hash(source)) return null

            val movieCategories = categoriesFromJson(root.optJSONArray("movieCategories"))
            val seriesCategories = categoriesFromJson(root.optJSONArray("seriesCategories"))
            val moviesByCategory = itemsMapFromJson(root.optJSONObject("moviesByCategory"))
            val seriesByCategory = itemsMapFromJson(root.optJSONObject("seriesByCategory"))
            val seriesById = seriesMapFromJson(root.optJSONObject("seriesById"))

            Cached(
                ParsedCatalog(
                    movieCategories = movieCategories,
                    seriesCategories = seriesCategories,
                    moviesByCategory = moviesByCategory,
                    seriesByCategory = seriesByCategory,
                    seriesById = seriesById,
                    ignored = root.optInt("ignored", 0)
                ),
                savedAt = root.optLong("savedAt", 0L)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun categoriesToJson(list: List<Category>): JSONArray = JSONArray().also { a ->
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name)) }
    }

    private fun categoriesFromJson(a: JSONArray?): List<Category> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Category(o.optString("id"), o.optString("name"))
        }
    }

    private fun itemToJson(item: PosterItem): JSONObject = JSONObject()
        .put("id", item.id)
        .put("name", item.name)
        .put("image", item.image ?: "")
        .put("isSeries", item.isSeries)
        .put("url", item.url ?: "")
        .put("headers", JSONObject(item.headers))

    private fun itemFromJson(o: JSONObject): PosterItem = PosterItem(
        id = o.optString("id"),
        name = o.optString("name"),
        image = o.optString("image").takeIf { it.isNotBlank() },
        isSeries = o.optBoolean("isSeries", false),
        url = o.optString("url").takeIf { it.isNotBlank() },
        headers = headersFromJson(o.optJSONObject("headers"))
    )

    private fun itemsMapToJson(map: Map<String, List<PosterItem>>): JSONObject = JSONObject().also { root ->
        for ((key, list) in map) {
            root.put(key, JSONArray().also { a -> list.forEach { a.put(itemToJson(it)) } })
        }
    }

    private fun itemsMapFromJson(root: JSONObject?): Map<String, List<PosterItem>> {
        if (root == null) return emptyMap()
        val result = linkedMapOf<String, List<PosterItem>>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val a = root.optJSONArray(key) ?: continue
            result[key] = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::itemFromJson) }
        }
        return result
    }

    private fun seriesMapToJson(map: Map<String, SeriesRecord>): JSONObject = JSONObject().also { root ->
        for ((id, record) in map) {
            val seasons = JSONArray()
            for (season in record.seasons) {
                val eps = JSONArray()
                for (ep in season.episodes) {
                    eps.put(
                        JSONObject()
                            .put("id", ep.id)
                            .put("title", ep.title)
                            .put("url", ep.url)
                            .put("headers", JSONObject(ep.headers))
                            .put("number", ep.number)
                            .put("season", ep.season)
                    )
                }
                seasons.put(JSONObject().put("number", season.number).put("episodes", eps))
            }
            root.put(id, JSONObject().put("item", itemToJson(record.item)).put("seasons", seasons))
        }
    }

    private fun seriesMapFromJson(root: JSONObject?): Map<String, SeriesRecord> {
        if (root == null) return emptyMap()
        val result = linkedMapOf<String, SeriesRecord>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val o = root.optJSONObject(id) ?: continue
            val item = o.optJSONObject("item")?.let(::itemFromJson) ?: continue
            val a = o.optJSONArray("seasons") ?: JSONArray()
            val seasons = (0 until a.length()).mapNotNull { i ->
                val s = a.optJSONObject(i) ?: return@mapNotNull null
                val seasonNo = s.optInt("number", i + 1)
                val eps = s.optJSONArray("episodes") ?: JSONArray()
                Season(
                    seasonNo,
                    (0 until eps.length()).mapNotNull { j ->
                        val e = eps.optJSONObject(j) ?: return@mapNotNull null
                        Episode(
                            id = e.optString("id"),
                            title = e.optString("title", "Episódio ${j + 1}"),
                            url = e.optString("url"),
                            headers = headersFromJson(e.optJSONObject("headers")),
                            number = e.optInt("number", j + 1),
                            season = e.optInt("season", seasonNo)
                        )
                    }.sortedBy { it.number }
                )
            }.sortedBy { it.number }
            result[id] = SeriesRecord(item, seasons)
        }
        return result
    }

    private fun headersFromJson(o: JSONObject?): Map<String, String> {
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

    private fun hash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val REFRESH_POOL = Executors.newSingleThreadExecutor()
        private val REFRESHING = AtomicBoolean(false)
        private val LOCK = Any()
        @Volatile private var sharedSourceHash: String = ""
        @Volatile private var sharedCatalog: ParsedCatalog? = null
    }
}
