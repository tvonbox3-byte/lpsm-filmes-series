package com.lpsm.vod

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import com.lpsm.vod.data.CatalogApi
import com.lpsm.vod.databinding.ActivitySeriesBinding
import com.lpsm.vod.model.Episode
import com.lpsm.vod.model.Season
import com.lpsm.vod.ui.EpisodeAdapter
import com.lpsm.vod.ui.SeasonAdapter
import java.util.concurrent.Executors
import org.json.JSONObject

class SeriesActivity : Activity() {

    private lateinit var b: ActivitySeriesBinding
    private val pool = Executors.newSingleThreadExecutor()
    private lateinit var api: CatalogApi
    private lateinit var seasonsAdapter: SeasonAdapter
    private lateinit var episodesAdapter: EpisodeAdapter
    private var currentSeason: Season? = null

    private var seriesNameValue = "Série"
    private var seriesImageValue: String? = null
    private var seriesAdultValue = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        b = ActivitySeriesBinding.inflate(layoutInflater)
        setContentView(b.root)
        api = CatalogApi(this)

        val seriesId = intent.getStringExtra("seriesId").orEmpty()
        val name = intent.getStringExtra("name").orEmpty().ifBlank { "Série" }
        val image = intent.getStringExtra("image")

        seriesNameValue = name
        seriesImageValue = image
        seriesAdultValue = intent.getBooleanExtra("adult", false)

        b.seriesTitle.text = name
        b.seriesPoster.load(image) { crossfade(true) }
        b.seriesSubtitle.text = "Carregando temporadas..."

        seasonsAdapter = SeasonAdapter(
            onSelected = { showSeason(it, focusEpisodes = false) },
            onDown = { showSeason(it, focusEpisodes = true) }
        )

        episodesAdapter = EpisodeAdapter(
            onClick = { play(it) },
            onUpFromFirst = { focusSelectedSeason() }
        )

        b.seasons.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        b.seasons.adapter = seasonsAdapter
        b.seasons.itemAnimator = null
        b.seasons.preserveFocusAfterLayout = true

        b.episodes.layoutManager = LinearLayoutManager(this)
        b.episodes.adapter = episodesAdapter
        b.episodes.itemAnimator = null
        b.episodes.preserveFocusAfterLayout = true

        if (seriesId.isBlank()) {
            b.seriesSubtitle.text = "Série inválida."
            return
        }

        b.progress.visibility = View.VISIBLE

        pool.execute {
            try {
                val seasons = api.seasons(seriesId)

                runOnUiThread {
                    b.progress.visibility = View.GONE

                    if (seasons.isEmpty()) {
                        b.seriesSubtitle.text = "Nenhum episódio encontrado para esta série."
                        return@runOnUiThread
                    }

                    val episodeCount = seasons.sumOf { it.episodes.size }
                    b.seriesSubtitle.text =
                        "${seasons.size} temporada${if (seasons.size == 1) "" else "s"} • " +
                        "$episodeCount episódio${if (episodeCount == 1) "" else "s"}"

                    seasonsAdapter.submit(seasons)
                    showSeason(seasons.first(), focusEpisodes = false)

                    // O primeiro foco sempre cai na Temporada 1/primeira temporada encontrada.
                    b.seasons.postDelayed({
                        focusSelectedSeason()
                    }, 180L)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    b.progress.visibility = View.GONE
                    b.seriesSubtitle.text = "Erro ao carregar episódios: ${e.message}"
                }
            }
        }
    }

    private fun showSeason(season: Season, focusEpisodes: Boolean) {
        currentSeason = season
        episodesAdapter.submit(season.episodes)

        b.episodeHeader.text =
            "Temporada ${season.number} • ${season.episodes.size} " +
            "episódio${if (season.episodes.size == 1) "" else "s"}"

        if (focusEpisodes && season.episodes.isNotEmpty()) {
            focusFirstEpisode()
        }
    }

    private fun focusSelectedSeason() {
        val position = seasonsAdapter.selectedPosition().coerceAtLeast(0)
        b.seasons.scrollToPosition(position)

        b.seasons.postDelayed({
            val holder = b.seasons.findViewHolderForAdapterPosition(position)
            if (holder?.itemView?.requestFocus() != true) {
                // Segunda tentativa após o layout da RecyclerView.
                b.seasons.postDelayed({
                    b.seasons.findViewHolderForAdapterPosition(position)
                        ?.itemView
                        ?.requestFocus()
                }, 120L)
            }
        }, 40L)
    }

    private fun focusFirstEpisode() {
        b.episodes.scrollToPosition(0)

        b.episodes.postDelayed({
            val holder = b.episodes.findViewHolderForAdapterPosition(0)
            if (holder?.itemView?.requestFocus() != true) {
                b.episodes.postDelayed({
                    b.episodes.findViewHolderForAdapterPosition(0)
                        ?.itemView
                        ?.requestFocus()
                }, 120L)
            }
        }, 40L)
    }

    private fun play(ep: Episode) {
        if (ep.url.isBlank()) return

        val seasonNumber = currentSeason?.number ?: ep.season
        val displayName =
            "$seriesNameValue • T$seasonNumber E${ep.number}" +
            if (
                ep.title.isNotBlank() &&
                !ep.title.startsWith("Episódio", true)
            ) {
                " • ${ep.title}"
            } else {
                ""
            }

        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("url", ep.url)
                .putExtra("title", displayName)
                .putExtra("headers", JSONObject(ep.headers).toString())
                .putExtra("contentKey", "e:${ep.id}")
                .putExtra("contentName", displayName)
                .putExtra("contentImage", seriesImageValue)
                .putExtra("contentModeSeries", true)
                .putExtra("contentAdult", seriesAdultValue)
        )
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }
}
