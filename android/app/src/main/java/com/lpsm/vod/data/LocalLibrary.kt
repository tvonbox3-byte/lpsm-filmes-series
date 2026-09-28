package com.lpsm.vod.data

import android.content.Context
import com.lpsm.vod.model.PosterItem
import org.json.JSONArray
import org.json.JSONObject

class LocalLibrary(context: Context) {
    private val prefs =
        context.getSharedPreferences("vod_local_library_v2", Context.MODE_PRIVATE)

    private val favoritesKey = "favorites_v2"
    private val continueKey = "continue_v2"

    private fun readArray(key: String): JSONArray = try {
        JSONArray(prefs.getString(key, "[]") ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }

    private fun writeArray(key: String, value: JSONArray) {
        prefs.edit().putString(key, value.toString()).apply()
    }

    private fun headersToJson(headers: Map<String, String>): JSONObject {
        val o = JSONObject()
        headers.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) o.put(key, value)
        }
        return o
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

    private fun posterToJson(
        item: PosterItem,
        modeSeries: Boolean,
        adult: Boolean
    ): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("name", item.name)
        put("image", item.image ?: "")
        put("isSeries", item.isSeries)
        put("url", item.url ?: "")
        put("headers", headersToJson(item.headers))
        put("modeSeries", modeSeries)
        put("adult", adult)
        put("updatedAt", System.currentTimeMillis())
    }

    private fun jsonToPoster(o: JSONObject): PosterItem =
        PosterItem(
            id = o.optString("id"),
            name = o.optString("name"),
            image = o.optString("image").takeIf { it.isNotBlank() },
            isSeries = o.optBoolean("isSeries", false),
            url = o.optString("url").takeIf { it.isNotBlank() },
            headers = headersFromJson(o.optJSONObject("headers"))
        )

    fun isFavorite(id: String, modeSeries: Boolean): Boolean {
        val a = readArray(favoritesKey)
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (
                o.optString("id") == id &&
                o.optBoolean("modeSeries", false) == modeSeries
            ) return true
        }
        return false
    }

    /** true = adicionado, false = removido */
    fun toggleFavorite(
        item: PosterItem,
        modeSeries: Boolean,
        adult: Boolean
    ): Boolean {
        val old = readArray(favoritesKey)
        val next = JSONArray()
        var removed = false

        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            val same =
                o.optString("id") == item.id &&
                o.optBoolean("modeSeries", false) == modeSeries

            if (same) removed = true
            else next.put(o)
        }

        if (removed) {
            writeArray(favoritesKey, next)
            return false
        }

        val withNew = JSONArray()
        withNew.put(posterToJson(item, modeSeries, adult))

        for (i in 0 until next.length()) {
            if (withNew.length() >= 300) break
            withNew.put(next.optJSONObject(i) ?: continue)
        }

        writeArray(favoritesKey, withNew)
        return true
    }

    fun removeFavorite(id: String, modeSeries: Boolean) {
        val old = readArray(favoritesKey)
        val next = JSONArray()

        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            if (
                o.optString("id") == id &&
                o.optBoolean("modeSeries", false) == modeSeries
            ) continue

            next.put(o)
        }

        writeArray(favoritesKey, next)
    }

    fun favoriteItems(modeSeries: Boolean): List<PosterItem> {
        val a = readArray(favoritesKey)
        val result = mutableListOf<PosterItem>()

        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optBoolean("modeSeries", false) != modeSeries) continue
            result += jsonToPoster(o)
        }

        return result
    }

    fun favoriteAdultIds(modeSeries: Boolean): Set<String> {
        val a = readArray(favoritesKey)
        val result = linkedSetOf<String>()

        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optBoolean("modeSeries", false) != modeSeries) continue
            if (o.optBoolean("adult", false)) result += o.optString("id")
        }

        return result
    }

    fun saveProgress(
        contentKey: String,
        name: String,
        image: String?,
        url: String,
        headers: Map<String, String>,
        modeSeries: Boolean,
        adult: Boolean,
        positionMs: Long,
        durationMs: Long
    ) {
        if (contentKey.isBlank() || url.isBlank()) return

        // Só entra em "Continuar assistindo" depois de realmente assistir.
        if (positionMs < 12_000L) return

        // Se terminou praticamente tudo, remove da lista.
        if (durationMs > 0L && positionMs >= (durationMs * 0.95).toLong()) {
            removeContinue(contentKey)
            return
        }

        val old = readArray(continueKey)
        val next = JSONArray()

        next.put(
            JSONObject().apply {
                put("contentKey", contentKey)
                put("name", name)
                put("image", image ?: "")
                put("url", url)
                put("headers", headersToJson(headers))
                put("modeSeries", modeSeries)
                put("adult", adult)
                put("positionMs", positionMs)
                put("durationMs", durationMs)
                put("updatedAt", System.currentTimeMillis())
            }
        )

        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            if (o.optString("contentKey") == contentKey) continue
            if (next.length() >= 100) break
            next.put(o)
        }

        writeArray(continueKey, next)
    }

    fun resumePosition(contentKey: String): Long {
        val a = readArray(continueKey)

        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("contentKey") == contentKey) {
                return o.optLong("positionMs", 0L)
            }
        }

        return 0L
    }

    fun removeContinue(contentKey: String) {
        val old = readArray(continueKey)
        val next = JSONArray()

        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            if (o.optString("contentKey") == contentKey) continue
            next.put(o)
        }

        writeArray(continueKey, next)
    }

    fun continueItems(modeSeries: Boolean): List<PosterItem> {
        val a = readArray(continueKey)
        val result = mutableListOf<PosterItem>()

        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optBoolean("modeSeries", false) != modeSeries) continue

            val key = o.optString("contentKey")
            if (key.isBlank()) continue

            result += PosterItem(
                id = key,
                name = o.optString("name").ifBlank { "Continuar assistindo" },
                image = o.optString("image").takeIf { it.isNotBlank() },
                isSeries = false,
                url = o.optString("url").takeIf { it.isNotBlank() },
                headers = headersFromJson(o.optJSONObject("headers"))
            )
        }

        return result
    }

    fun continueAdultIds(modeSeries: Boolean): Set<String> {
        val a = readArray(continueKey)
        val result = linkedSetOf<String>()

        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optBoolean("modeSeries", false) != modeSeries) continue
            if (o.optBoolean("adult", false)) {
                result += o.optString("contentKey")
            }
        }

        return result
    }
}
