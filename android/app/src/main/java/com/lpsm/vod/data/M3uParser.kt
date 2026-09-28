package com.lpsm.vod.data

import com.lpsm.vod.model.Category
import com.lpsm.vod.model.Episode
import com.lpsm.vod.model.PosterItem
import com.lpsm.vod.model.Season
import java.io.BufferedReader
import java.net.URLDecoder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

data class SeriesRecord(
    val item: PosterItem,
    val seasons: List<Season>
)

data class ParsedCatalog(
    val movieCategories: List<Category>,
    val seriesCategories: List<Category>,
    val moviesByCategory: Map<String, List<PosterItem>>,
    val seriesByCategory: Map<String, List<PosterItem>>,
    val seriesById: Map<String, SeriesRecord>,
    val ignored: Int = 0
) {
    val movieCount: Int get() = moviesByCategory.values.sumOf { it.size }
    val seriesCount: Int get() = seriesById.size
    val episodeCount: Int get() = seriesById.values.sumOf { record -> record.seasons.sumOf { it.episodes.size } }
}

object M3uParser {
    private const val DEFAULT_MAX_ITEMS = 250_000

    private data class Pending(
        val name: String,
        val logo: String?,
        var group: String,
        val headers: MutableMap<String, String> = linkedMapOf()
    )

    private data class EpisodeInfo(
        val season: Int,
        val episode: Int,
        val marker: String
    )

    private data class MutableSeries(
        val id: String,
        var name: String,
        var image: String?,
        val seasons: LinkedHashMap<Int, MutableList<Episode>> = linkedMapOf()
    )

    private val attrRegex = Regex("""([\w-]+)=\"([^\"]*)\"""")
    private val seriesGroupRegex = Regex("""\b(series?|seriados?|temporadas?|novelas?|doramas?|animes?|tv\s*shows?)\b""", RegexOption.IGNORE_CASE)
    private val movieGroupRegex = Regex("""\b(filmes?|movies?|cinema|vod|lan[çc]amentos?|cat[aá]logo\s*vod)\b""", RegexOption.IGNORE_CASE)
    private val liveGroupRegex = Regex("""\b(canais?|ao\s*vivo|live|tv\s*aberta|abertos?|esportes?|sports?|futebol|not[ií]cias?|news|r[aá]dios?|ppv|24h|24\s*horas|bbb|fazenda|premiere|combate)\b""", RegexOption.IGNORE_CASE)

    private val episodePatterns = listOf(
        Regex("""\bS\s*(\d{1,3})\s*[.\-_ ]*E\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE),
        Regex("""\bT\s*(\d{1,3})\s*[.\-_ ]*E\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE),
        // exige 2+ dígitos no episódio para não confundir títulos como "4x4"
        Regex("""\b(\d{1,2})\s*x\s*(\d{2,4})\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:TEMP(?:ORADA)?|SEASON)\s*(\d{1,3})\D+(?:EP(?:IS[ÓO]DIO|ISODE)?|E)\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(\d{1,3})\s*[ªº]?\s*TEMPORADA\D+(?:EP(?:IS[ÓO]DIO|ISODE)?|E)\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE)
    )

    private val onlyEpisodeRegex = Regex("""\b(?:EP(?:IS[ÓO]DIO|ISODE)?|E)\s*[.\-_ ]*(\d{1,4})\b""", RegexOption.IGNORE_CASE)
    private val groupSeasonRegex = Regex("""\b(?:TEMPORADA|SEASON)\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE)

    fun parse(reader: BufferedReader, maxItems: Int = DEFAULT_MAX_ITEMS): ParsedCatalog {
        val movieCategories = linkedMapOf<String, Category>()
        val seriesCategories = linkedMapOf<String, Category>()
        val moviesByCategory = linkedMapOf<String, MutableList<PosterItem>>()
        val seriesIdsByCategory = linkedMapOf<String, LinkedHashSet<String>>()
        val seriesIndex = linkedMapOf<String, MutableSeries>()

        var pending: Pending? = null
        var fallbackGroup = ""
        var accepted = 0
        var ignored = 0

        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.trim()
            if (line.isBlank()) continue

            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val attrs = parseAttrs(line)
                pending = Pending(
                    name = displayName(line, attrs),
                    logo = attrs["tvg-logo"]?.trim()?.takeIf { it.isNotBlank() },
                    group = attrs["group-title"]?.trim().orEmpty().ifBlank { fallbackGroup }
                )
                continue
            }

            if (line.startsWith("#EXTGRP:", ignoreCase = true)) {
                fallbackGroup = line.substringAfter(':').trim()
                if (pending != null && pending.group.isBlank()) pending.group = fallbackGroup
                continue
            }

            if (line.startsWith("#")) {
                applyDirective(line, pending)
                continue
            }

            if (!line.startsWith("http://", true) && !line.startsWith("https://", true)) {
                pending = null
                continue
            }

            val meta = pending ?: continue
            pending = null
            if (accepted >= maxItems) continue

            val (streamUrl, streamHeaders) = splitUrlAndHeaders(line, meta.headers)
            val ep = episodeInfo(meta.name, meta.group)
            val kind = classify(streamUrl, meta.group, ep)
            if (kind == "live" || kind == "other") {
                ignored++
                continue
            }

            accepted++

            if (kind == "movie") {
                val cat = makeCategory("movie", meta.group)
                movieCategories[cat.id] = cat
                val list = moviesByCategory.getOrPut(cat.id) { mutableListOf() }
                list += PosterItem(
                    id = hash("movie|$streamUrl"),
                    name = meta.name,
                    image = meta.logo,
                    isSeries = false,
                    url = streamUrl,
                    headers = streamHeaders
                )
                continue
            }

            val info = ep ?: EpisodeInfo(groupSeason(meta.group) ?: 1, 1, "")
            val seriesName = cleanSeriesName(meta.name, info)
            val normalizedKey = seriesKey(seriesName).ifBlank { fold(seriesName) }
            val seriesId = hash("series|$normalizedKey")
            val category = makeCategory("series", meta.group)
            seriesCategories[category.id] = category

            val series = seriesIndex.getOrPut(seriesId) {
                MutableSeries(
                    id = seriesId,
                    name = seriesName,
                    image = meta.logo
                )
            }
            if (series.image.isNullOrBlank() && !meta.logo.isNullOrBlank()) series.image = meta.logo

            seriesIdsByCategory.getOrPut(category.id) { linkedSetOf() }.add(seriesId)

            val episodes = series.seasons.getOrPut(info.season) { mutableListOf() }
            if (episodes.none { it.url == streamUrl }) {
                episodes += Episode(
                    id = hash("episode|$streamUrl"),
                    title = cleanEpisodeTitle(meta.name, info, seriesName),
                    url = streamUrl,
                    headers = streamHeaders,
                    number = info.episode,
                    season = info.season
                )
            }
        }

        val immutableSeries = linkedMapOf<String, SeriesRecord>()
        for ((id, s) in seriesIndex) {
            val seasons = s.seasons.entries
                .sortedBy { it.key }
                .map { (number, eps) ->
                    Season(number, eps.sortedWith(compareBy<Episode> { it.number }.thenBy { it.title }))
                }
            immutableSeries[id] = SeriesRecord(
                item = PosterItem(
                    id = id,
                    name = s.name,
                    image = s.image,
                    isSeries = true
                ),
                seasons = seasons
            )
        }

        val seriesByCategory = linkedMapOf<String, List<PosterItem>>()
        for ((categoryId, ids) in seriesIdsByCategory) {
            seriesByCategory[categoryId] = ids.mapNotNull { immutableSeries[it]?.item }
        }

        return ParsedCatalog(
            movieCategories = movieCategories.values.toList(),
            seriesCategories = seriesCategories.values.toList(),
            moviesByCategory = moviesByCategory.mapValues { it.value.toList() },
            seriesByCategory = seriesByCategory,
            seriesById = immutableSeries,
            ignored = ignored
        )
    }

    private fun parseAttrs(line: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (m in attrRegex.findAll(line)) {
            result[m.groupValues[1].lowercase(Locale.ROOT)] = m.groupValues[2]
        }
        return result
    }

    private fun displayName(line: String, attrs: Map<String, String>): String {
        val byComma = line.substringAfter(',', "").trim()
        return attrs["tvg-name"]?.trim()?.takeIf { it.isNotBlank() }
            ?: byComma.takeIf { it.isNotBlank() }
            ?: "Sem título"
    }

    private fun episodeInfo(name: String, group: String): EpisodeInfo? {
        for (pattern in episodePatterns) {
            val m = pattern.find(name) ?: continue
            return EpisodeInfo(
                season = m.groupValues[1].toIntOrNull()?.coerceAtLeast(1) ?: 1,
                episode = m.groupValues[2].toIntOrNull()?.coerceAtLeast(1) ?: 1,
                marker = m.value
            )
        }

        val only = onlyEpisodeRegex.find(name)
        if (only != null) {
            return EpisodeInfo(
                season = groupSeason(group) ?: 1,
                episode = only.groupValues[1].toIntOrNull()?.coerceAtLeast(1) ?: 1,
                marker = only.value
            )
        }
        return null
    }

    private fun groupSeason(group: String): Int? =
        groupSeasonRegex.find(group)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1)

    private fun cleanSeriesName(name: String, ep: EpisodeInfo): String {
        val original = name.trim()
        if (ep.marker.isBlank()) return stripSeasonSuffix(original)

        val index = original.indexOf(ep.marker, ignoreCase = true)
        if (index > 0) {
            val before = original.substring(0, index)
                .replace(Regex("""[\s._|:\-–—\[\]()]+$"""), "")
                .trim()
            if (before.length >= 2) return stripSeasonSuffix(before)
        }

        val without = original.replace(ep.marker, "", ignoreCase = true)
            .replace(Regex("""\b(?:TEMPORADA|SEASON)\s*\d{1,3}\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\b(?:EPIS[ÓO]DIO|EPISODE|EP)\s*\d{1,4}\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""[\s._|:\-–—]+$"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
        return stripSeasonSuffix(without.ifBlank { original })
    }

    private fun stripSeasonSuffix(value: String): String = value
        .replace(Regex("""\s*[-–—:]?\s*(?:TEMPORADA|SEASON)\s*\d{1,3}\s*$""", RegexOption.IGNORE_CASE), "")
        .trim()
        .ifBlank { value.trim() }

    private fun cleanEpisodeTitle(name: String, ep: EpisodeInfo, seriesName: String): String {
        val original = name.trim()
        if (ep.marker.isNotBlank()) {
            val index = original.indexOf(ep.marker, ignoreCase = true)
            if (index >= 0) {
                val after = original.substring(index + ep.marker.length)
                    .replace(Regex("""^[\s._|:\-–—\[\]()]+"""), "")
                    .trim()
                if (after.length >= 2 && !after.equals(seriesName, ignoreCase = true)) return after
            }
        }
        return "Episódio ${ep.episode}"
    }

    private fun seriesKey(value: String): String = fold(value)
        .replace(Regex("""\b(?:temporada|season)\s*\d{1,3}\b""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""\b(?:s|t)\s*\d{1,3}\b""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""[\[\](){}._|:\-–—]+"""), " ")
        .replace(Regex("""\s{2,}"""), " ")
        .trim()

    private fun classify(url: String, group: String, ep: EpisodeInfo?): String {
        val u = fold(url)
        val g = fold(group)
        val path = u.substringBefore('?')
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)

        if (u.contains("/series/")) return "series"
        if (u.contains("/movie/")) return "movie"
        if (u.contains("/live/")) return "live"

        if (seriesGroupRegex.containsMatchIn(g)) return "series"
        if (movieGroupRegex.containsMatchIn(g)) return "movie"
        if (liveGroupRegex.containsMatchIn(g)) return "live"

        if (ep != null) return "series"

        if (ext in setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "mpg", "mpeg")) return "movie"
        if (ext == "m3u8" || ext == "ts") return "live"

        return "other"
    }

    private fun makeCategory(kind: String, group: String): Category {
        val title = group.trim().ifBlank { if (kind == "series") "Séries" else "Filmes" }
        return Category(hash("$kind|$title"), title)
    }

    private fun applyDirective(line: String, pending: Pending?) {
        if (pending == null) return
        val lower = line.lowercase(Locale.ROOT)
        when {
            lower.startsWith("#extvlcopt:http-user-agent=") ->
                setHeader(pending.headers, "User-Agent", line.substringAfter('='))
            lower.startsWith("#extvlcopt:http-referrer=") || lower.startsWith("#extvlcopt:http-referer=") ->
                setHeader(pending.headers, "Referer", line.substringAfter('='))
            lower.startsWith("#kodiprop:inputstream.adaptive.stream_headers=") ||
                lower.startsWith("#kodiprop:inputstream.adaptive.manifest_headers=") ->
                parseHeaderPairs(line.substringAfter('='), pending.headers)
            lower.startsWith("#exthttp:") -> {
                val raw = line.substringAfter(':').trim()
                val pairRegex = Regex("""\"([^\"]+)\"\s*:\s*\"([^\"]*)\"""")
                for (m in pairRegex.findAll(raw)) {
                    setHeader(pending.headers, m.groupValues[1], m.groupValues[2])
                }
            }
        }
    }

    private fun splitUrlAndHeaders(raw: String, inherited: Map<String, String>): Pair<String, Map<String, String>> {
        val headers = linkedMapOf<String, String>()
        headers.putAll(inherited)
        val pipe = raw.indexOf('|')
        if (pipe < 0) return raw.trim() to headers
        val url = raw.substring(0, pipe).trim()
        parseHeaderPairs(raw.substring(pipe + 1), headers)
        return url to headers
    }

    private fun parseHeaderPairs(text: String, target: MutableMap<String, String>) {
        for (part in text.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            setHeader(target, part.substring(0, eq), part.substring(eq + 1))
        }
    }

    private fun setHeader(target: MutableMap<String, String>, key: String, value: String) {
        val k = key.trim().let { if (it.equals("referrer", true)) "Referer" else it }
        if (k.isBlank()) return
        val v = try {
            URLDecoder.decode(value.trim().replace("+", "%20"), "UTF-8")
        } catch (_: Exception) {
            value.trim()
        }
        if (v.isNotBlank()) target[k] = v
    }

    private fun fold(value: String): String = Normalizer.normalize(
        value.trim().lowercase(Locale.ROOT),
        Normalizer.Form.NFD
    ).replace(Regex("""\p{Mn}+"""), "")

    private fun hash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(20)
    }
}
