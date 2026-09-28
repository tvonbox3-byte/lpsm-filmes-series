package com.lpsm.vod

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
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
    private val episodesAdapter = EpisodeAdapter { play(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySeriesBinding.inflate(layoutInflater)
        setContentView(b.root)
        api = CatalogApi(this)

        val seriesId = intent.getStringExtra("seriesId").orEmpty()
        val name = intent.getStringExtra("name").orEmpty().ifBlank { "Série" }
        val image = intent.getStringExtra("image")

        b.seriesTitle.text = name
        b.seriesPoster.load(image) { crossfade(true) }
        b.seriesSubtitle.text = "Carregando temporadas e episódios..."

        seasonsAdapter = SeasonAdapter { showSeason(it) }
        b.seasons.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        b.seasons.adapter = seasonsAdapter
        b.episodes.layoutManager = LinearLayoutManager(this)
        b.episodes.adapter = episodesAdapter

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
                    } else {
                        val episodeCount = seasons.sumOf { it.episodes.size }
                        b.seriesSubtitle.text = "${seasons.size} temporadas • $episodeCount episódios"
                        seasonsAdapter.submit(seasons)
                        showSeason(seasons.first())
                        b.seasons.post {
                            b.seasons.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                                ?: b.seasons.requestFocus()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    b.progress.visibility = View.GONE
                    b.seriesSubtitle.text = "Erro ao carregar episódios: ${e.message}"
                }
            }
        }
    }

    private fun showSeason(season: Season) {
        episodesAdapter.submit(season.episodes)
        b.episodeHeader.text = "Temporada ${season.number} • ${season.episodes.size} episódios"
    }

    private fun play(ep: Episode) {
        if (ep.url.isBlank()) return
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("url", ep.url)
                .putExtra("title", ep.title)
                .putExtra("headers", JSONObject(ep.headers).toString())
        )
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }
}
