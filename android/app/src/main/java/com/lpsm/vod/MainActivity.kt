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
import com.lpsm.vod.data.CatalogApi
import com.lpsm.vod.data.DeviceApi
import com.lpsm.vod.databinding.ActivityMainBinding
import com.lpsm.vod.model.*
import com.lpsm.vod.ui.CategoryAdapter
import com.lpsm.vod.ui.PosterAdapter
import java.text.Normalizer
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity: Activity() {
    private lateinit var b: ActivityMainBinding
    private val pool = Executors.newFixedThreadPool(3)
    private val cats = CategoryAdapter { selectCategory(it) }
    private val posters = PosterAdapter { openItem(it) }
    private var modeSeries = false
    private lateinit var api: CatalogApi
    private val pin = "0202"
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeat = object : Runnable {
        override fun run() {
            pool.execute { DeviceApi.heartbeat(this@MainActivity) }
            heartbeatHandler.postDelayed(this, 20000)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        api = CatalogApi(this)
        b.categories.layoutManager = LinearLayoutManager(this)
        b.categories.adapter = cats
        b.grid.layoutManager = GridLayoutManager(this, if (resources.configuration.smallestScreenWidthDp >= 600) 6 else 4)
        b.grid.adapter = posters
        b.moviesTab.setOnClickListener { modeSeries = false; loadCategories() }
        b.seriesTab.setOnClickListener { modeSeries = true; loadCategories() }
        b.settingsBtn.text = "ATIVAÇÃO"
        b.settingsBtn.setOnClickListener { startActivityForResult(Intent(this, SetupActivity::class.java), 9) }
        verifyAndLoad()
        UpdateManager.check(this)
        heartbeatHandler.post(heartbeat)
    }

    private fun verifyAndLoad() {
        b.progress.visibility = View.VISIBLE
        b.status.text = "Conectando ao painel..."
        pool.execute {
            try {
                DeviceApi.heartbeat(this)
                val result = DeviceApi.fetchActivation(this)
                runOnUiThread {
                    if (result.active) {
                        loadCategories()
                    } else {
                        b.progress.visibility = View.GONE
                        startActivityForResult(Intent(this, SetupActivity::class.java), 9)
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    b.progress.visibility = View.GONE
                    b.status.text = "Não foi possível conectar ao servidor. Tente novamente."
                }
            }
        }
    }

    private fun isAdult(name: String): Boolean {
        val n = Normalizer.normalize(name.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace("\\p{Mn}+".toRegex(), "")
        return listOf("adult", "xxx", "18+", "porno", "erotic").any { n.contains(it) }
    }

    private fun askPin(ok: () -> Unit) {
        val input = EditText(this).apply { inputType = 2; hint = "PIN" }
        AlertDialog.Builder(this)
            .setTitle("Conteúdo adulto")
            .setView(input)
            .setPositiveButton("Entrar") { _, _ -> if (input.text.toString() == pin) ok() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun loadCategories() {
        b.progress.visibility = View.VISIBLE
        b.status.text = if (modeSeries) "Carregando categorias de séries..." else "Carregando categorias de filmes..."
        pool.execute {
            try {
                val (list, summary) = api.categories(modeSeries)
                runOnUiThread {
                    b.progress.visibility = View.GONE
                    cats.submit(list)
                    posters.submit(emptyList())
                    b.status.text = "$summary • ${list.size} categorias"
                    if (list.isNotEmpty()) b.categories.requestFocus()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    b.progress.visibility = View.GONE
                    b.status.text = "Erro: ${e.message}"
                    if ((e.message ?: "").contains("ativ", true) || (e.message ?: "").contains("paus", true)) {
                        startActivityForResult(Intent(this, SetupActivity::class.java), 9)
                    }
                }
            }
        }
    }

    private fun selectCategory(c: Category) {
        if (isAdult(c.name)) askPin { loadCategory(c) } else loadCategory(c)
    }

    private fun loadCategory(c: Category) {
        b.progress.visibility = View.VISIBLE
        b.status.text = "${c.name} — carregando..."
        pool.execute {
            try {
                val list = api.items(modeSeries, c.id)
                runOnUiThread {
                    b.progress.visibility = View.GONE
                    posters.submit(list)
                    b.status.text = "${c.name} — ${list.size} títulos"
                    if (list.isNotEmpty()) b.grid.requestFocus()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    b.progress.visibility = View.GONE
                    b.status.text = "Erro: ${e.message}"
                }
            }
        }
    }

    private fun openItem(item: PosterItem) {
        if (!item.isSeries) {
            val url = item.url ?: return
            play(url, item.headers)
            return
        }
        b.progress.visibility = View.VISIBLE
        pool.execute {
            try {
                val seasons = api.seasons(item.id)
                runOnUiThread {
                    b.progress.visibility = View.GONE
                    showSeasons(item.name, seasons)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    b.progress.visibility = View.GONE
                    b.status.text = "Erro na série: ${e.message}"
                }
            }
        }
    }

    private fun showSeasons(name: String, seasons: List<Season>) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24,16,24,16)
        }
        seasons.forEach { s ->
            root.addView(TextView(this).apply {
                text = "Temporada ${s.number}"
                textSize = 20f
                setPadding(8,14,8,8)
            })
            s.episodes.forEach { ep ->
                root.addView(TextView(this).apply {
                    text = ep.title
                    textSize = 17f
                    setPadding(16,12,16,12)
                    isFocusable = true
                    setOnClickListener {
                        play(ep.url, ep.headers)
                    }
                })
            }
        }
        AlertDialog.Builder(this).setTitle(name).setView(root).setNegativeButton("Fechar", null).show()
    }

    private fun play(url: String, headers: Map<String, String>) {
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra("url", url)
            .putExtra("headers", JSONObject(headers).toString())
        startActivity(intent)
    }

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r,c,d)
        if (r == 9 && c == RESULT_OK) verifyAndLoad()
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacksAndMessages(null)
        pool.shutdownNow()
        super.onDestroy()
    }
}
