package com.lpsm.vod.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lpsm.vod.databinding.ItemEpisodeBinding
import com.lpsm.vod.model.Episode

class EpisodeAdapter(
    private val onClick: (Episode) -> Unit,
    private val onUpFromFirst: () -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

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
        val number = if (ep.number > 0) ep.number else position + 1

        h.b.number.text = number.toString().padStart(2, '0')
        h.b.title.text = ep.title.ifBlank { "Episódio $number" }

        h.b.root.setOnClickListener { onClick(ep) }

        h.b.root.onFocusChangeListener = View.OnFocusChangeListener { v, focused ->
            v.animate()
                .scaleX(if (focused) 1.025f else 1f)
                .scaleY(if (focused) 1.025f else 1f)
                .setDuration(100)
                .start()
            v.translationZ = if (focused) 12f else 0f
        }

        val confirm = RemoteConfirm()
        h.b.root.setOnKeyListener { _, keyCode, event ->
            if (confirm.handle(h.b.root, keyCode, event)) return@setOnKeyListener true
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            val p = h.bindingAdapterPosition
            if (p == RecyclerView.NO_POSITION) return@setOnKeyListener false

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (p == 0) {
                        if (event.repeatCount == 0) onUpFromFirst()
                        true
                    } else false
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    p == itemCount - 1
                }
                else -> false
            }
        }
    }

    class VH(val b: ItemEpisodeBinding) : RecyclerView.ViewHolder(b.root)
}
