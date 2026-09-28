package com.lpsm.vod.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lpsm.vod.databinding.ItemEpisodeBinding
import com.lpsm.vod.model.Episode

class EpisodeAdapter(private val onClick: (Episode) -> Unit): RecyclerView.Adapter<EpisodeAdapter.VH>() {
    private var items = listOf<Episode>()

    fun submit(v: List<Episode>) {
        items = v
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val ep = items[position]
        val n = if (ep.number > 0) ep.number else position + 1
        h.b.number.text = n.toString().padStart(2, '0')
        h.b.title.text = ep.title
        h.b.root.setOnClickListener { onClick(ep) }
        h.b.root.onFocusChangeListener = View.OnFocusChangeListener { v, f ->
            v.animate().scaleX(if (f) 1.025f else 1f).scaleY(if (f) 1.025f else 1f).setDuration(100).start()
            v.translationZ = if (f) 12f else 0f
        }
    }

    class VH(val b: ItemEpisodeBinding): RecyclerView.ViewHolder(b.root)
}
