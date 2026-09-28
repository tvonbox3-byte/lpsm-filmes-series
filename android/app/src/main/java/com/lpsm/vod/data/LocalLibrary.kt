package com.lpsm.vod.data

import android.content.Context
import com.lpsm.vod.model.PosterItem
import org.json.JSONArray
import org.json.JSONObject

class LocalLibrary(context: Context) {
    private val prefs = context.getSharedPreferences("vod_local_library_v1", Context.MODE_PRIVATE)

    private val favoritesKey = "favorites"
    private val continueKey = "continue"

    private fun readArray(key: String): JSONArray = try {
        JSONArray(prefs.getString(key, "[]") ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }

    private fun writeArray(key: String, array: JSONArray) {
        prefs.edit().putString(key, array.toString()).apply()
    }

    private fun headersToJson(headers: Map<String, String>): JSONObject {
        val o = JSONObject()
        headers.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotBlank()) o.put(k, v)
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

    private fun itemToJson(item: PosterItem, modeSeries: Boolean, adult: Boolean): JSONObject =
        JSONObject().apply {
            put("id", item.id)
            put("name", item.name)
            put("image", item.image ?: "")
            put("isSeries", item.isSeries)
            put("modeSeries", modeSeries)
            put("adult", adult)
            put("url", item.url ?: "")
            put("headers", headersToJson(item.headers))
            put("updatedAt", System.currentTimeMillis())
        }

    private fun jsonToItem(o: JSONObject): PosterItem = PosterItem(
        id = o.optString("id"),
        name = o.optString("name"),
        image = o.optString("image").takeIf { it.isNotBlank() },
        isSeries = o.optBoolean("isSeries", false),
        url = o.optString("url").takeIf { it.isNotBlank() },
        headers = headersFromJson(o.optJSONObject("headers")),
        adult = o.optBoolean("adult", false)
    )

    fun isFavorite(id: String, modeSeries: Boolean): Boolean {
        val a = readArray(favoritesKey)
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("id") == id && o.optBoolean("modeSeries") == modeSeries) return true
        }
        return false
    }

    /** Returns true when the item was added; false when it was removed. */
    fun toggleFavorite(item: PosterItem, modeSeries: Boolean, adult: Boolean = item.adult): Boolean {
        val a = readArray(favoritesKey)
        val next = JSONArray()
        var removed = false

        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("id") == item.id && o.optBoolean("modeSeries") == modeSeries) {
                removed = true
            } else {
                next.put(o)
            }
        }

        if (!removed) {
            val withNew = JSONArray()
            withNew.put(itemToJson(item, modeSeries, adult))
            for (i in 0 until next.length().coerceAtMost(299)) withNew.put(next.getJSONObject(i))
            writeArray(favoritesKey, withNew)
            return true
        }

        writeArray(favoritesKey, next)
        return false
    }

    fun removeFavorite(id: String, modeSeries: Boolean) {
        val a = readArray(favoritesKey)
        val next = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("id") == id && o.optBoolean("modeSeries") == modeSeries) continue
            next.put(o)
        }
        writeArray(favoritesKey, next)
    }

    fun favoriteItems(modeSeries: Boolean): List<PosterItem> {
        val a = readArray(favoritesKey)
        val result = mutableListOf<PosterItem>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optBoolean("modeSeries") != modeSeries) continue
            result += jsonToItem(o)
        }
        return result
    }

    fun saveProgress(
        contentKey: String,
        itemId: String,
        name: String,
        image: String?,
        url: String,
        headers: Map<String, String>,
        modeSeries: Boolean,
        adult: Boolean,
        positionMs: Long,
        durationMs: Long
    ) {
        if (contentKey.isBlank() || itemId.isBlank() || url.isBlank()) return

        // Não polui "Continuar assistindo" com algo aberto por poucos segundos.
        if (positionMs < 15_000L) return

        if (durationMs > 0L && positionMs >= (durationMs * 0.95).toLong()) {
            removeContinue(contentKey)
            return
        }

        val old = readArray(continueKey)
        val next = JSONArray()
        val current = JSONObject().apply {
            put("contentKey", contentKey)
            put("id", itemId)
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
        next.put(current)

        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            if (o.optString("contentKey") == contentKey) continue
            if (next.length() >= 80) break
            next.put(o)
        }

        writeArray(continueKey, next)
    }

    fun resumePosition(contentKey: String): Long {
        val a = readArray(continueKey)
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("contentKey") == contentKey) return o.optLong("positionMs", 0L)
        }
        return 0L
    }

    fun removeContinue(contentKey: String) {
        val a = readArray(continueKey)
        val next = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("contentKey") == contentKey) continue
            next.put(o)
        }
        writeArray(continueKey, next)
    }

    fun removeContinueItem(itemId: String, modeSeries: Boolean) {
        val a = readArray(continueKey)
        val next = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("id") == itemId && o.optBoolean("modeSeries") == modeSeries) continue
            next.put(o)
        }
        writeArray(continueKey, next)
    }

    fun continueItems(modeSeries: Boolean): List<PosterItem> {
        val a = readArray(continueKey)
        val result = mutableListOf<PosterItem>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optBoolean("modeSeries") != modeSeries) continue
            result += PosterItem(
                id = o.optString("id"),
                name = o.optString("name"),
                image = o.optString("image").takeIf { it.isNotBlank() },
                isSeries = false,
                url = o.optString("url").takeIf { it.isNotBlank() },
                headers = headersFromJson(o.optJSONObject("headers")),
                adult = o.optBoolean("adult", false)
            )
        }
        return result
    }
}
