package com.lpsm.vod

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.view.inputmethod.InputMethodManager
import android.content.Context
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import coil3.load
import coil3.request.crossfade
import com.lpsm.vod.data.CatalogApi
import com.lpsm.vod.data.DeviceApi
import com.lpsm.vod.databinding.ActivityMainBinding
import com.lpsm.vod.model.Category
import com.lpsm.vod.model.PosterItem
import com.lpsm.vod.ui.CategoryAdapter
import com.lpsm.vod.ui.PosterAdapter
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONObject

class MainActivity: Activity() {
    private lateinit var b: ActivityMainBinding
    private val pool = Executors.newFixedThreadPool(4)
    private val cats = CategoryAdapter(
        onClick = { selectCategory(it) },
        onDown = { loadCategory(it, focusGrid = true) }
    )
    private val posters = PosterAdapter(
        onClick = { openItem(it) },
        onFocus = { showHero(it) },
        onUp = { focusSelectedCategory() }
    )
    private var modeSeries = false
    private lateinit var api: CatalogApi
    private val pin = "0202"
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateCheck = object : Runnable {
        override fun run() {
            UpdateManager.check(this@MainActivity)
            updateHandler.postDelayed(this, 60 * 1000L)
        }
    }
    private val heartbeat = object : Runnable {
        override fun run() {
            pool.execute { DeviceApi.heartbeat(this@MainActivity) }
            heartbeatHandler.postDelayed(this, 30000)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        api = CatalogApi(this)

        b.categories.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        b.categories.adapter = cats

        // Grade estilo catálogo de streaming: várias capas visíveis ao mesmo tempo.
        val widthDp = resources.configuration.screenWidthDp
        val columns = when {
            widthDp >= 1200 -> 8
            widthDp >= 1000 -> 7
            widthDp >= 800 -> 6
            widthDp >= 600 -> 5
            else -> 3
        }

        posters.setSpanCount(columns)
        b.grid.layoutManager = GridLayoutManager(this, columns)
        b.grid.adapter = posters
        b.grid.setHasFixedSize(true)
        b.grid.setItemViewCacheSize(columns * 3)

        b.moviesTab.setOnClickListener { switchMode(false) }
        b.seriesTab.setOnClickListener { switchMode(true) }
        b.searchBtn.setOnClickListener { openSearch() }
        b.settingsBtn.text = "ATIVAÇÃO"
        b.settingsBtn.setOnClickListener { startActivityForResult(Intent(this, SetupActivity::class.java), 9) }

        // Só atravessa entre as áreas no PRIMEIRO toque.
        // Quando o usuário segura a seta, os eventos repetidos ficam na área atual
        // e não fazem o foco "pular" para outro lugar.
        val downToCategories = View.OnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN
            ) {
                if (event.repeatCount == 0) focusSelectedCategory()
                true
            } else {
                false
            }
        }
        b.moviesTab.setOnKeyListener(downToCategories)
        b.seriesTab.setOnKeyListener(downToCategories)
        b.searchBtn.setOnKeyListener(downToCategories)
        b.settingsBtn.setOnKeyListener(downToCategories)

        b.grid.itemAnimator = null
        b.grid.preserveFocusAfterLayout = true
        b.categories.itemAnimator = null
        b.categories.preserveFocusAfterLayout = true

        verifyAndLoad()
        // Verifica poucos segundos após abrir e continua verificando enquanto o app estiver em uso.
        updateHandler.postDelayed(updateCheck, 1500L)
        heartbeatHandler.post(heartbeat)
    }

    private fun openSearch() {
        val input = EditText(this).apply {
            hint = if (modeSeries) "Pesquisar séries" else "Pesquisar filmes"
            isSingleLine = true
            textSize = 18f
            setSelectAllOnFocus(true)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (modeSeries) "Pesquisar séries" else "Pesquisar filmes")
            .setView(input)
            .setPositiveButton("BUSCAR", null)
            .setNegativeButton("CANCELAR", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val query = input.text.toString().trim()
                if (query.length < 2) {
                    input.error = "Digite pelo menos 2 letras"
                    return@setOnClickListener
                }
                dialog.dismiss()
                searchCatalog(query)
            }

            input.requestFocus()
            input.postDelayed({
                try {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                } catch (_: Exception) { }
            }, 180L)
        }

        dialog.show()
    }

    private fun searchCatalog(query: String) {
        b.progress.visibility = View.VISIBLE
        b.sectionTitle.text = "Busca"
        b.status.text = "Pesquisando “$query”..."
        posters.submit(emptyList())
        cats.clearSelection()

        pool.execute {
            try {
                val list = api.search(modeSeries, query)
                runOnUiThread {
                    b.progress.visibility = View.GONE
                    posters.submit(list)
                    b.sectionTitle.text = "Resultados para “$query”"
                    b.status.text = "${list.size} resultado${if (list.size == 1) "" else "s"}"

                    if (list.isNotEmpty()) {
                        showHero(list.first())
                        focusFirstPoster()
                    } else {
                        b.heroTitle.text = "Nenhum resultado"
                        b.heroMeta.text = "Tente outro nome."
                        b.heroPoster.setImageDrawable(null)
                    }
                }
            } catch (_: Exception) {
                runOnUiThread { showM3uLoginError() }
            }
        }
    }

    private fun focusFirstPoster() {
        b.grid.scrollToPosition(0)
        b.grid.postDelayed({
            b.grid.findViewHolderForAdapterPosition(0)
                ?.itemView
                ?.requestFocus()
        }, 80L)
    }

    private fun showM3uLoginError() {
        b.progress.visibility = View.GONE
        cats.submit(emptyList())
        posters.submit(emptyList())
        b.status.text = "Login não está funcionando"
        b.heroTitle.text = "Login não está funcionando"
        b.heroMeta.text = "Verifique a lista M3U cadastrada no painel."
        b.heroPoster.setImageDrawable(null)
    }

    private fun switchMode(series: Boolean) {
        if (modeSeries == series && (b.grid.adapter?.itemCount ?: 0) > 0) return
        modeSeries = series
        updateTabs()
        loadCategories()
    }

    private fun updateTabs() {
        b.moviesTab.isSelected = !modeSeries
        b.seriesTab.isSelected = modeSeries
        b.sectionTitle.text = if (modeSeries) "Séries" else "Filmes"
    }

    private fun verifyAndLoad() {
        b.progress.visibility = View.VISIBLE

        // Se já existe ativação + catálogo no aparelho, abre na hora.
        // Isso evita esperar o Render Free "acordar" toda vez que o app abre.
        val cachedActivation = DeviceApi.fastCachedActivation(this)
        val cachedCatalog = api.hasCachedCategories(modeSeries)

        if (cachedActivation?.active == true && cachedCatalog) {
            b.status.text = "Abrindo catálogo salvo..."
            updateTabs()
            loadCategories()

            // Atualiza ativação/servidor em segundo plano, sem travar a Home.
            pool.execute {
                try {
                    DeviceApi.heartbeat(this)
                    DeviceApi.fetchActivation(this)
                    api.prefetchHome()
                } catch (_: Exception) { }
            }
            return
        }

        b.status.text = "Preparando seu catálogo..."
        pool.execute {
            try {
                DeviceApi.heartbeat(this)
                val result = DeviceApi.fetchActivation(this)
                runOnUiThread {
                    if (result.active) {
                        updateTabs()
                        loadCategories()
                        pool.execute { api.prefetchHome() }
                    } else {
                        b.progress.visibility = View.GONE
                        startActivityForResult(Intent(this, SetupActivity::class.java), 9)
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    showM3uLoginError()
                }
            }
        }
    }

    private fun isAdult(name: String): Boolean {
        val n = Normalizer.normalize(name.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
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
        b.status.text = if (modeSeries) "Carregando séries..." else "Carregando filmes..."
        posters.submit(emptyList())
        pool.execute {
            try {
                val (list, summary) = api.categories(modeSeries)
                runOnUiThread {
                    b.progress.visibility = View.GONE
                    cats.submit(list)
                    b.status.text = "$summary • ${list.size} categorias"
                    if (list.isNotEmpty()) {
                        cats.select(list.first())
                        loadCategory(list.first(), focusGrid = false)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    val msg = e.message.orEmpty()
                    if (
                        msg.contains("aguardando ativ", true) ||
                        msg.contains("paus", true) ||
                        msg.contains("expir", true)
                    ) {
                        b.progress.visibility = View.GONE
                        b.status.text = msg
                        startActivityForResult(Intent(this, SetupActivity::class.java), 9)
                    } else {
                        showM3uLoginError()
                    }
                }
            }
        }
    }

    private fun selectCategory(c: Category) {
        if (isAdult(c.name)) askPin { loadCategory(c, focusGrid = true) }
        else loadCategory(c, focusGrid = true)
    }

    private fun loadCategory(c: Category, focusGrid: Boolean) {
        cats.select(c)
        b.progress.visibility = View.VISIBLE
        b.sectionTitle.text = c.name
        b.status.text = "${c.name} • carregando..."
        pool.execute {
            try {
                val list = api.items(modeSeries, c.id)
                runOnUiThread {
                    b.progress.visibility = View.GONE
                    posters.submit(list)
                    b.status.text = "${c.name} • ${list.size} títulos"
                    if (list.isNotEmpty()) {
                        showHero(list.first())
                        if (focusGrid) {
                            focusFirstPoster()
                        }
                    } else {
                        clearHero()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    showM3uLoginError()
                }
            }
        }
    }

    private fun focusSelectedCategory() {
        val pos = cats.selectedPosition().coerceAtLeast(0)
        b.categories.scrollToPosition(pos)
        b.categories.postDelayed({
            b.categories.findViewHolderForAdapterPosition(pos)
                ?.itemView
                ?.requestFocus()
        }, 60L)
    }

    private fun showHero(item: PosterItem) {
        b.heroTitle.text = item.name
        b.heroMeta.text = if (item.isSeries) "SÉRIE • OK para abrir temporadas" else "FILME • OK para assistir"
        b.heroPoster.load(item.image) { crossfade(true) }
    }

    private fun clearHero() {
        b.heroTitle.text = if (modeSeries) "Séries" else "Filmes"
        b.heroMeta.text = "Escolha uma categoria"
        b.heroPoster.setImageDrawable(null)
    }

    private fun openItem(item: PosterItem) {
        if (!item.isSeries) {
            val url = item.url ?: return
            play(url, item.headers, item.name)
            return
        }
        startActivity(
            Intent(this, SeriesActivity::class.java)
                .putExtra("seriesId", item.id)
                .putExtra("name", item.name)
                .putExtra("image", item.image)
        )
    }

    private fun play(url: String, headers: Map<String, String>, title: String) {
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra("url", url)
            .putExtra("title", title)
            .putExtra("headers", JSONObject(headers).toString())
        startActivity(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            openSearch()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        UpdateManager.onResume(this)

        // Segunda tentativa rápida ajuda TV Boxes que demoram para conectar ao Wi-Fi.
        updateHandler.removeCallbacks(updateCheck)
        updateHandler.postDelayed(updateCheck, 12_000L)
    }

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r,c,d)
        if (r == 9 && c == RESULT_OK) verifyAndLoad()
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacksAndMessages(null)
        updateHandler.removeCallbacksAndMessages(null)
        pool.shutdownNow()
        super.onDestroy()
    }
}
