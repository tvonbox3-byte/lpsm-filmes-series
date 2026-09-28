package com.lpsm.vod.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lpsm.vod.databinding.ItemSeasonBinding
import com.lpsm.vod.model.Season

class SeasonAdapter(private val onClick: (Season) -> Unit): RecyclerView.Adapter<SeasonAdapter.VH>() {
    private var items = listOf<Season>()
    private var selected = -1

    fun submit(v: List<Season>) {
        items = v
        selected = if (v.isEmpty()) -1 else 0
        notifyDataSetChanged()
    }

    fun select(index: Int) {
        if (index !in items.indices) return
        selected = index
        notifyDataSetChanged()
        onClick(items[index])
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemSeasonBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val s = items[position]
        h.b.name.text = "Temporada ${s.number}"
        h.b.root.isSelected = position == selected
        h.b.name.isSelected = position == selected
        h.b.name.setTypeface(null, if (position == selected) Typeface.BOLD else Typeface.NORMAL)
        h.b.root.setOnClickListener { select(position) }
        h.b.root.onFocusChangeListener = View.OnFocusChangeListener { v, f ->
            v.animate().scaleX(if (f) 1.05f else 1f).scaleY(if (f) 1.05f else 1f).setDuration(100).start()
        }
    }

    class VH(val b: ItemSeasonBinding): RecyclerView.ViewHolder(b.root)
}
