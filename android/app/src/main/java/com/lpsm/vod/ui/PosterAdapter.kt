package com.lpsm.vod.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.lpsm.vod.databinding.ItemPosterBinding
import com.lpsm.vod.model.PosterItem

class PosterAdapter(
    private val onClick: (PosterItem) -> Unit,
    private val onFocus: (PosterItem) -> Unit = {}
): RecyclerView.Adapter<PosterAdapter.VH>() {
    private var items = listOf<PosterItem>()

    fun submit(v: List<PosterItem>) {
        items = v
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(p: ViewGroup, v: Int) =
        VH(ItemPosterBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val x = items[i]
        h.b.name.text = x.name
        h.b.poster.load(x.image) {
            crossfade(true)
        }
        h.b.root.setOnClickListener { onClick(x) }
        h.b.root.onFocusChangeListener = View.OnFocusChangeListener { v, focused ->
            v.animate()
                .scaleX(if (focused) 1.075f else 1f)
                .scaleY(if (focused) 1.075f else 1f)
                .setDuration(120)
                .start()
            v.translationZ = if (focused) 16f else 0f
            if (focused) onFocus(x)
        }
    }

    class VH(val b: ItemPosterBinding): RecyclerView.ViewHolder(b.root)
}
