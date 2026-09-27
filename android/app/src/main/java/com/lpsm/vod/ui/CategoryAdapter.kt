package com.lpsm.vod.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lpsm.vod.databinding.ItemCategoryBinding
import com.lpsm.vod.model.Category

class CategoryAdapter(private val onClick: (Category) -> Unit): RecyclerView.Adapter<CategoryAdapter.VH>() {
    private var items = listOf<Category>()
    fun submit(v: List<Category>) { items = v; notifyDataSetChanged() }
    override fun onCreateViewHolder(p: ViewGroup, v: Int) = VH(ItemCategoryBinding.inflate(LayoutInflater.from(p.context), p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, i: Int) { h.b.name.text = items[i].name; h.b.root.setOnClickListener { onClick(items[i]) } }
    class VH(val b: ItemCategoryBinding): RecyclerView.ViewHolder(b.root)
}
