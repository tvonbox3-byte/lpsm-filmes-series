package com.lpsm.vod

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.lpsm.vod.data.SourceConfig
import com.lpsm.vod.data.DeviceApi
import com.lpsm.vod.data.XtreamApi
import com.lpsm.vod.databinding.ActivityMainBinding
import com.lpsm.vod.model.*
import com.lpsm.vod.ui.CategoryAdapter
import com.lpsm.vod.ui.PosterAdapter
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity: Activity() {
    private lateinit var b: ActivityMainBinding
    private val pool = Executors.newFixedThreadPool(3)
    private val cats = CategoryAdapter { selectCategory(it) }
    private val posters = PosterAdapter { openItem(it) }
    private var modeSeries = false
    private var api: XtreamApi? = null
    private val pin = "0202"
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeat = object : Runnable {
        override fun run() {
            pool.execute { DeviceApi.heartbeat(this@MainActivity) }
            heartbeatHandler.postDelayed(this, 20000)
        }
    }

    override fun onCreate(s: Bundle?) { super.onCreate(s); b = ActivityMainBinding.inflate(layoutInflater); setContentView(b.root)
        b.categories.layoutManager = LinearLayoutManager(this); b.categories.adapter = cats
        b.grid.layoutManager = GridLayoutManager(this, if (resources.configuration.smallestScreenWidthDp >= 600) 6 else 4); b.grid.adapter = posters
        b.moviesTab.setOnClickListener { modeSeries = false; loadCategories() }
        b.seriesTab.setOnClickListener { modeSeries = true; loadCategories() }
        b.settingsBtn.text = "ATIVAÇÃO"
        b.settingsBtn.setOnClickListener { startActivityForResult(Intent(this, SetupActivity::class.java), 9) }
        initSource()
        UpdateManager.check(this)
        heartbeatHandler.post(heartbeat)
    }

    private fun initSource() {
        val cfg = SourceConfig.load(this)
        if (cfg == null) {
            startActivityForResult(Intent(this, SetupActivity::class.java), 9)
            return
        }
        api = XtreamApi(cfg)
        loadCategories()

        // Atualiza ativação/fonte em segundo plano sem impedir o catálogo em cache/local.
        pool.execute {
            try {
                val result = DeviceApi.fetchActivation(this)
                if (result.active && result.source != null) {
                    SourceConfig.save(this, result.source)
                    api = XtreamApi(result.source)
                } else if (!result.active) {
                    SourceConfig.clear(this)
                    runOnUiThread { startActivityForResult(Intent(this, SetupActivity::class.java), 9) }
                }
            } catch (_: Exception) { }
        }
    }

    private fun isAdult(name: String): Boolean {
        val n = Normalizer.normalize(name.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace("\\p{Mn}+".toRegex(), "")
        return listOf("adult", "xxx", "18+", "porno", "erotic").any { n.contains(it) }
    }

    private fun askPin(ok: () -> Unit) {
        val input = EditText(this).apply { inputType = 2; hint = "PIN" }
        AlertDialog.Builder(this).setTitle("Conteúdo adulto").setView(input).setPositiveButton("Entrar") { _, _ -> if (input.text.toString() == pin) ok() }.setNegativeButton("Cancelar", null).show()
    }

    private fun loadCategories() {
        val a = api ?: return
        b.progress.visibility = View.VISIBLE; b.status.text = if (modeSeries) "Carregando categorias de séries..." else "Carregando categorias de filmes..."
        pool.execute {
            try {
                val list = if (modeSeries) a.seriesCategories() else a.movieCategories()
                runOnUiThread { b.progress.visibility = View.GONE; cats.submit(list); posters.submit(emptyList()); b.status.text = "${list.size} categorias"; if (list.isNotEmpty()) b.categories.requestFocus() }
            } catch (e: Exception) { runOnUiThread { b.progress.visibility = View.GONE; b.status.text = "Erro: ${e.message}" } }
        }
    }

    private fun selectCategory(c: Category) {
        if (isAdult(c.name)) { askPin { loadCategory(c) } } else loadCategory(c)
    }
    private fun loadCategory(c: Category) {
        val a = api ?: return
        b.progress.visibility = View.VISIBLE; b.status.text = "${c.name} — carregando..."
        pool.execute {
            try {
                val list = if (modeSeries) a.series(c.id) else a.movies(c.id)
                runOnUiThread { b.progress.visibility = View.GONE; posters.submit(list); b.status.text = "${c.name} — ${list.size} títulos"; if (list.isNotEmpty()) b.grid.requestFocus() }
            } catch (e: Exception) { runOnUiThread { b.progress.visibility = View.GONE; b.status.text = "Erro: ${e.message}" } }
        }
    }

    private fun openItem(item: PosterItem) {
        val a = api ?: return
        if (!item.isSeries) { startActivity(Intent(this, PlayerActivity::class.java).putExtra("url", a.movieUrl(item))); return }
        b.progress.visibility = View.VISIBLE
        pool.execute {
            try {
                val seasons = a.seasons(item.id)
                runOnUiThread { b.progress.visibility = View.GONE; showSeasons(item.name, seasons) }
            } catch (e: Exception) { runOnUiThread { b.progress.visibility = View.GONE; b.status.text = "Erro na série: ${e.message}" } }
        }
    }

    private fun showSeasons(name: String, seasons: List<Season>) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,16,24,16) }
        seasons.forEach { s ->
            root.addView(TextView(this).apply { text = "Temporada ${s.number}"; textSize = 20f; setPadding(8,14,8,8) })
            s.episodes.forEach { ep -> root.addView(TextView(this).apply { text = ep.title; textSize = 17f; setPadding(16,12,16,12); isFocusable = true; setOnClickListener { startActivity(Intent(this@MainActivity, PlayerActivity::class.java).putExtra("url", api!!.episodeUrl(ep))) } }) }
        }
        AlertDialog.Builder(this).setTitle(name).setView(root).setNegativeButton("Fechar", null).show()
    }

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r,c,d)
        if (r == 9 && c == RESULT_OK) initSource()
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacksAndMessages(null)
        pool.shutdownNow()
        super.onDestroy()
    }
}
