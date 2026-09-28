package com.lpsm.vod.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lpsm.vod.databinding.ItemCategoryBinding
import com.lpsm.vod.model.Category

class CategoryAdapter(
    private val onClick: (Category) -> Unit,
    private val onDown: (Category) -> Unit = {}
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    private var items = listOf<Category>()
    private var selectedId: String? = null

    init { setHasStableIds(true) }

    fun submit(v: List<Category>) {
        items = v
        if (selectedId != null && items.none { it.id == selectedId }) {
            selectedId = null
        }
        notifyDataSetChanged()
    }

    fun select(category: Category) {
        selectedId = category.id
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedId = null
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long =
        items.getOrNull(position)?.id?.hashCode()?.toLong() ?: RecyclerView.NO_ID

    fun selectedPosition(): Int {
        if (items.isEmpty()) return -1
        val p = items.indexOfFirst { it.id == selectedId }
        return if (p >= 0) p else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        h.b.name.text = item.name
        h.b.root.isSelected = item.id == selectedId

        h.b.root.setOnClickListener {
            selectedId = item.id
            notifyDataSetChanged()
            onClick(item)
        }

        h.b.root.onFocusChangeListener = View.OnFocusChangeListener { v, focused ->
            v.animate()
                .scaleX(if (focused) 1.06f else 1f)
                .scaleY(if (focused) 1.06f else 1f)
                .setDuration(110)
                .start()
        }

        h.b.root.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // O primeiro toque entra na grade. Repetições de tecla segurada
                // são consumidas para não recarregar a categoria e voltar ao topo.
                if (event.repeatCount == 0) {
                    selectedId = item.id
                    notifyDataSetChanged()
                    onDown(item)
                }
                true
            } else {
                false
            }
        }
    }

    class VH(val b: ItemCategoryBinding) : RecyclerView.ViewHolder(b.root)
}
