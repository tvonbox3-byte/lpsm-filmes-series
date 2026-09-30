package com.lpsm.vod

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
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
    private var touchDevice = false

    private lateinit var b: ActivitySeriesBinding
    private val pool = Executors.newFixedThreadPool(2)
    private var foregroundGeneration = 0
    @Volatile private var loadGeneration = 0
    private lateinit var api: CatalogApi
    private lateinit var seasonsAdapter: SeasonAdapter
    private lateinit var episodesAdapter: EpisodeAdapter
    private var currentSeason: Season? = null
    private var restoredSeasonNumber: Int? = null

    private var seriesNameValue = "Série"
    private var seriesImageValue: String? = null
    private var seriesAdultValue = false
    private var seriesIdValue = ""
    private var loadFailed = false
    private var loadingSeasons = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        foregroundGeneration = (application as VodApplication).foregroundGeneration

        restoredSeasonNumber = savedInstanceState?.getInt("seasonNumber", -1)?.takeIf { it >= 0 }
        touchDevice = DeviceUi.isTouchDevice(this)
        if (!touchDevice) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        b = if (touchDevice) {
            ActivitySeriesBinding.bind(layoutInflater.inflate(R.layout.activity_series_mobile, null))
        } else {
            ActivitySeriesBinding.inflate(layoutInflater)
        }
        setContentView(b.root)
        api = CatalogApi(this)

        val seriesId = intent.getStringExtra("seriesId").orEmpty()
        seriesIdValue = seriesId
        val name = intent.getStringExtra("name").orEmpty().ifBlank { "Série" }
        val image = intent.getStringExtra("image")

        seriesNameValue = name
        seriesImageValue = image
        seriesAdultValue = intent.getBooleanExtra("adult", false)

        b.seriesTitle.text = name
        b.seriesPoster.load(image) { size(320, 480); crossfade(true) }
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

        loadSeasons()
    }

    private fun loadSeasons() {
        if (loadingSeasons || seriesIdValue.isBlank()) return

        loadingSeasons = true
        val requestGeneration = ++loadGeneration
        loadFailed = false
        currentSeason = null
        seasonsAdapter.submit(emptyList())
        episodesAdapter.submit(emptyList())
        b.progress.visibility = View.VISIBLE
        b.seriesSubtitle.text = "Carregando temporadas..."

        pool.execute {
            var loaded: List<Season>? = null
            var lastError: Exception? = null

            for (attempt in 1..3) {
                if (requestGeneration != loadGeneration || Thread.currentThread().isInterrupted) return@execute
                try {
                    loaded = api.seasons(seriesIdValue)
                    if (!loaded.isNullOrEmpty()) break
                } catch (e: Exception) {
                    lastError = e
                }

                if (attempt < 3) {
                    try {
                        Thread.sleep(900L * attempt)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }

            runOnUiThread {
                if (isFinishing || isDestroyed || requestGeneration != loadGeneration) return@runOnUiThread
                loadingSeasons = false
                b.progress.visibility = View.GONE

                val seasons = loaded

                if (!seasons.isNullOrEmpty()) {
                    loadFailed = false

                    val episodeCount =
                        seasons.sumOf { it.episodes.size }

                    b.seriesSubtitle.text =
                        "${seasons.size} temporada${if (seasons.size == 1) "" else "s"} • " +
                        "$episodeCount episódio${if (episodeCount == 1) "" else "s"}"

                    seasonsAdapter.submit(seasons)
                    val selectedIndex = seasons.indexOfFirst { it.number == restoredSeasonNumber }.coerceAtLeast(0)
                    seasonsAdapter.select(selectedIndex, notify = false)
                    showSeason(seasons[selectedIndex], focusEpisodes = false)

                    b.seasons.postDelayed({
                        focusSelectedSeason()
                    }, 180L)

                    return@runOnUiThread
                }

                loadFailed = true

                val detail =
                    lastError?.message
                        ?.takeIf { it.isNotBlank() }
                        ?: "Servidor de episódios temporariamente indisponível"

                b.seriesSubtitle.text =
                    "$detail • pressione OK para tentar novamente"
            }
        }
    }

    private fun showSeason(season: Season, focusEpisodes: Boolean) {
        currentSeason = season
        restoredSeasonNumber = season.number
        episodesAdapter.submit(season.episodes)

        b.episodeHeader.text =
            "Temporada ${season.number} • ${season.episodes.size} " +
            "episódio${if (season.episodes.size == 1) "" else "s"}"

        if (focusEpisodes && season.episodes.isNotEmpty()) {
            focusFirstEpisode()
        }
    }

    private fun focusSelectedSeason() {
        if (touchDevice && b.root.isInTouchMode) return
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
        if (touchDevice && b.root.isInTouchMode) return
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
                .putExtra("alternateUrl", ep.alternateUrl)
                .putExtra("title", displayName)
                .putExtra("headers", JSONObject(ep.headers).toString())
                .putExtra("contentKey", "e:${ep.id}")
                .putExtra("contentName", displayName)
                .putExtra("contentImage", seriesImageValue)
                .putExtra("contentModeSeries", true)
                .putExtra("contentAdult", seriesAdultValue)
        )
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {
        if (
            loadFailed &&
            (
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER
            )
        ) {
            loadSeasons()
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("seasonNumber", currentSeason?.number ?: restoredSeasonNumber ?: -1)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        loadGeneration++
        pool.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val generation = (application as VodApplication).foregroundGeneration
        if (generation != foregroundGeneration) {
            foregroundGeneration = generation
            loadingSeasons = false
            loadSeasons()
        }
    }
}
