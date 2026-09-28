package com.lpsm.vod.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import com.lpsm.vod.databinding.ItemPosterBinding
import com.lpsm.vod.model.PosterItem

class PosterAdapter(
    private val onClick: (PosterItem) -> Unit,
    private val onLongClick: (PosterItem) -> Unit = {},
    private val onFocus: (PosterItem) -> Unit = {},
    private val onUp: (PosterItem) -> Unit = {}
) : RecyclerView.Adapter<PosterAdapter.VH>() {

    private var items = listOf<PosterItem>()
    private var spanCount = 1

    init {
        setHasStableIds(true)
    }

    fun setSpanCount(value: Int) {
        spanCount = value.coerceAtLeast(1)
    }

    fun submit(v: List<PosterItem>) {
        items = v
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long =
        items.getOrNull(position)?.id?.hashCode()?.toLong() ?: RecyclerView.NO_ID

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemPosterBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]

        h.b.name.text = item.name
        h.b.poster.contentDescription = item.name

        // Capa padrão 2:3. Evita o aspecto "foto quadrada/recortada" que estava aparecendo.
        h.b.poster.post {
            val width = h.b.poster.width
            if (width > 0) {
                val wanted = (width * 1.50f).toInt()
                if (wanted > 0 && h.b.poster.layoutParams.height != wanted) {
                    h.b.poster.layoutParams = h.b.poster.layoutParams.apply {
                        height = wanted
                    }
                }
            }
        }

        h.b.poster.load(item.image) {
            crossfade(true)
        }

        h.b.root.setOnClickListener { onClick(item) }
        h.b.root.setOnLongClickListener {
            onLongClick(item)
            true
        }

        var longOkHandled = false

        h.b.root.onFocusChangeListener = View.OnFocusChangeListener { v, focused ->
            v.animate()
                .scaleX(if (focused) 1.055f else 1f)
                .scaleY(if (focused) 1.055f else 1f)
                .setDuration(90)
                .start()

            v.translationZ = if (focused) 18f else 0f
            h.b.name.isSelected = focused

            if (focused) onFocus(item)
        }

        h.b.root.setOnKeyListener { _, keyCode, event ->
            val p = h.bindingAdapterPosition
            if (p == RecyclerView.NO_POSITION) return@setOnKeyListener false

            if (
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER
            ) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (
                            !longOkHandled &&
                            (event.isLongPress || event.repeatCount >= 1)
                        ) {
                            longOkHandled = true
                            onLongClick(item)
                            return@setOnKeyListener true
                        }
                    }

                    KeyEvent.ACTION_UP -> {
                        if (longOkHandled) {
                            longOkHandled = false
                            return@setOnKeyListener true
                        }
                    }
                }
            }

            // Botão MENU também serve como atalho de favorito em controles
            // que não enviam corretamente o "OK segurado".
            if (
                keyCode == KeyEvent.KEYCODE_MENU &&
                event.action == KeyEvent.ACTION_DOWN &&
                event.repeatCount == 0
            ) {
                onLongClick(item)
                return@setOnKeyListener true
            }

            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (p < spanCount) {
                        if (event.repeatCount == 0) onUp(item)
                        true
                    } else {
                        false
                    }
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    p + spanCount >= itemCount
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    p == 0
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    p == itemCount - 1
                }

                else -> false
            }
        }
    }

    class VH(val b: ItemPosterBinding) : RecyclerView.ViewHolder(b.root)
}
