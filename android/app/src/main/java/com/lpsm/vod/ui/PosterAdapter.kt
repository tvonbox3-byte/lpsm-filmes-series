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
    private val onFocus: (PosterItem) -> Unit = {},
    private val onUp: (PosterItem) -> Unit = {}
) : RecyclerView.Adapter<PosterAdapter.VH>() {

    private var items = listOf<PosterItem>()

    fun submit(v: List<PosterItem>) {
        items = v
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemPosterBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]

        h.b.name.text = item.name
        h.b.poster.load(item.image) {
            crossfade(true)
        }

        h.b.root.setOnClickListener { onClick(item) }

        h.b.root.onFocusChangeListener = View.OnFocusChangeListener { v, focused ->
            v.animate()
                .scaleX(if (focused) 1.075f else 1f)
                .scaleY(if (focused) 1.075f else 1f)
                .setDuration(120)
                .start()
            v.translationZ = if (focused) 16f else 0f
            if (focused) onFocus(item)
        }

        h.b.root.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                onUp(item)
                true
            } else {
                false
            }
        }
    }

    class VH(val b: ItemPosterBinding) : RecyclerView.ViewHolder(b.root)
}
