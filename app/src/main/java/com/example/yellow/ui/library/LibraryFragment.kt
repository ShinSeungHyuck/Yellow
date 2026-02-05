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
        val fav = FavoritesStore.getAll(requireContext())
        val items = fav.map {
            SongAdapter.Item(song = it, subtitle = it.queryTitle.takeIf { qt -> qt != it.title }, isFavorite = true)
        }
        adapter.submit(items)
        binding.txtEmpty.isVisible = items.isEmpty()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
