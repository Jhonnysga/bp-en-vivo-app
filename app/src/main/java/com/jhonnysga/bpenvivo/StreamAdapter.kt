package com.jhonnysga.bpenvivo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Filas de la lista: miniatura, título, canal y distintivo "EN VIVO".
 * Los items son focusables para que también se puedan navegar con D-pad.
 */
class StreamAdapter(private val onClick: (StreamItem) -> Unit) :
    RecyclerView.Adapter<StreamAdapter.VH>() {

    private val items = mutableListOf<StreamItem>()

    fun submit(newItems: List<StreamItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_stream, parent, false)
        return VH(v, onClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    class VH(view: View, private val onClick: (StreamItem) -> Unit) :
        RecyclerView.ViewHolder(view) {

        private val thumb: ImageView = view.findViewById(R.id.thumb)
        private val title: TextView = view.findViewById(R.id.title)
        private val channel: TextView = view.findViewById(R.id.channel)
        private var item: StreamItem? = null

        init {
            view.isFocusable = true
            view.isClickable = true
            view.setOnClickListener { item?.let(onClick) }
        }

        fun bind(s: StreamItem) {
            item = s
            title.text = s.title
            if (s.channel.isNullOrBlank()) {
                channel.visibility = View.GONE
            } else {
                channel.visibility = View.VISIBLE
                channel.text = s.channel
            }
            ImageLoader.load(s.thumbnail, thumb)
        }
    }
}
