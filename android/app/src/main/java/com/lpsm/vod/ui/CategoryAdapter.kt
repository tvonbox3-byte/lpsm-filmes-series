package com.lpsm.vod.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lpsm.vod.databinding.ItemCategoryBinding
import com.lpsm.vod.model.Category

class CategoryAdapter(
    private val onClick: (Category) -> Unit
): RecyclerView.Adapter<CategoryAdapter.VH>() {
    private var items = listOf<Category>()
    private var selectedId: String? = null

    fun submit(v: List<Category>) {
        items = v
        if (selectedId != null && items.none { it.id == selectedId }) selectedId = null
        notifyDataSetChanged()
    }

    fun select(category: Category) {
        selectedId = category.id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(p: ViewGroup, v: Int) =
        VH(ItemCategoryBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val item = items[i]
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
    }

    class VH(val b: ItemCategoryBinding): RecyclerView.ViewHolder(b.root)
}
