package com.lpsm.vod.model

data class Category(val id: String, val name: String)

data class PosterItem(
    val id: String,
    val name: String,
    val image: String?,
    val extension: String? = null,
    val isSeries: Boolean = false,
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val adult: Boolean = false
)

data class Episode(
    val id: String,
    val title: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val number: Int = 0,
    val season: Int = 1
)

data class Season(val number: Int, val episodes: List<Episode>)
