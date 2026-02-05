package com.example.yellow.ui.search

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.yellow.R
import com.example.yellow.data.FavoritesStore
import com.example.yellow.data.Song

class SongAdapter(
    private val onClick: (Item) -> Unit,
    private val onToggleFavorite: (Item) -> Unit
) : ListAdapter<SongAdapter.Item, SongAdapter.VH>(DIFF) {

    data class Item(
        val song: Song,
        val subtitle: String? = null,
        val isFavorite: Boolean
    )

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.item_title)
        val subtitle: TextView = v.findViewById(R.id.item_subtitle)
        val star: ImageButton = v.findViewById(R.id.item_star)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.title.text = item.song.title

        val sub = item.subtitle
        if (sub.isNullOrBlank()) {
            holder.subtitle.visibility = View.GONE
        } else {
            holder.subtitle.visibility = View.VISIBLE
            holder.subtitle.text = sub
        }

        holder.star.setImageResource(
            if (item.isFavorite) android.R.drawable.btn_star_big_on
            else android.R.drawable.btn_star_big_off
        )

        holder.itemView.setOnClickListener { onClick(item) }
        holder.star.setOnClickListener { onToggleFavorite(item) }
    }

    fun submit(items: List<Item>) = submitList(items)

    fun updateFavorite(songId: String, isFav: Boolean) {
        val cur = currentList.toMutableList()
        val idx = cur.indexOfFirst { it.song.id == songId }
        if (idx >= 0) {
            cur[idx] = cur[idx].copy(isFavorite = isFav)
            submitList(cur)
        }
    }

    fun refreshFavorites(context: Context) {
        val fav = FavoritesStore.getAll(context).map { it.id }.toSet()
        val cur = currentList.map { it.copy(isFavorite = fav.contains(it.song.id)) }
        submitList(cur)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean =
                oldItem.song.id == newItem.song.id

            override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean =
                oldItem == newItem
        }
    }
}
