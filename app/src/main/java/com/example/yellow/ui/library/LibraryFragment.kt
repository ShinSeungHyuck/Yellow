package com.example.yellow.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.yellow.R
import com.example.yellow.data.FavoritesStore
import com.example.yellow.databinding.FragmentLibraryBinding
import com.example.yellow.ui.search.SongAdapter

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: SongAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = SongAdapter(
            onClick = { item ->
                findNavController().navigate(
                    R.id.pianoFragment,
                    bundleOf(
                        "title" to item.song.title,
                        "melodyUrl" to item.song.melodyUrl,
                        "midiUrl" to item.song.midiUrl,
                        "queryTitle" to item.song.queryTitle
                    )
                )
            },
            onToggleFavorite = { item ->
                FavoritesStore.toggle(requireContext(), item.song)
                loadFavorites()
            }
        )

        binding.recyclerFavorites.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerFavorites.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadFavorites()
    }

    private fun loadFavorites() {
        val entries = FavoritesStore.getAllEntries(requireContext())

        val items = entries.map { e ->
            val s = e.song
            // queryTitle에 "곡명 - 아티스트" 원본이 저장되므로 아티스트 추출은 queryTitle 우선 사용
            val fullTitle = s.queryTitle.ifBlank { s.title }
            val (mainTitle, artist) = splitTitleArtist(fullTitle)

            val displaySong = s.copy(
                title = mainTitle
            )

            SongAdapter.Item(
                song = displaySong,
                subtitle = artist,
                isFavorite = true,
                keyOffset = e.keyOffset
            )
        }

        adapter.submit(items)
        binding.txtEmpty.isVisible = items.isEmpty()
    }

    private fun splitTitleArtist(full: String): Pair<String, String?> {
        val parts = full.split(" - ", limit = 2)
        return if (parts.size == 2) {
            parts[0].trim() to parts[1].trim()
        } else {
            full to null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}