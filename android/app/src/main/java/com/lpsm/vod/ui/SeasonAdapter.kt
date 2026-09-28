package com.lpsm.vod.ui

import android.graphics.Typeface
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lpsm.vod.databinding.ItemSeasonBinding
import com.lpsm.vod.model.Season

class SeasonAdapter(
    private val onSelected: (Season) -> Unit,
    private val onDown: (Season) -> Unit
) : RecyclerView.Adapter<SeasonAdapter.VH>() {

    private var items = listOf<Season>()
    private var selected = -1

    fun submit(v: List<Season>) {
        items = v
        selected = if (v.isEmpty()) -1 else 0
        notifyDataSetChanged()
    }

    fun selectedPosition(): Int = selected

    fun select(index: Int, notify: Boolean = true) {
        if (index !in items.indices) return
        val changed = selected != index
        selected = index
        if (changed) notifyDataSetChanged()
        if (notify) onSelected(items[index])
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemSeasonBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val season = items[position]
        h.b.name.text = "Temporada ${season.number}"

        val isSelected = position == selected
        h.b.root.isSelected = isSelected
        h.b.name.isSelected = isSelected
        h.b.name.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)

        // OK seleciona a temporada.
        h.b.root.setOnClickListener {
            select(h.bindingAdapterPosition)
        }

        // Na TV Box, somente mover o foco para outra temporada já troca os episódios,
        // como em apps de streaming para TV.
        h.b.root.onFocusChangeListener = View.OnFocusChangeListener { v, focused ->
            v.animate()
                .scaleX(if (focused) 1.06f else 1f)
                .scaleY(if (focused) 1.06f else 1f)
                .setDuration(100)
                .start()

            if (focused) {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION && p != selected) {
                    select(p)
                }
            }
        }

        // Seta para baixo entra diretamente nos episódios da temporada selecionada.
        h.b.root.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) {
                    if (event.repeatCount == 0) {
                        if (p != selected) select(p)
                        onDown(items[p])
                    }
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
    }

    class VH(val b: ItemSeasonBinding) : RecyclerView.ViewHolder(b.root)
}
