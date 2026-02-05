package com.example.yellow.ui.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.yellow.R
import com.example.yellow.data.FavoritesStore
import com.example.yellow.data.SongSearchRepository
import com.example.yellow.databinding.FragmentSearchBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private val repo = SongSearchRepository()
    private lateinit var adapter: SongAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
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
                val nowFav = FavoritesStore.toggle(requireContext(), item.song)
                adapter.updateFavorite(item.song.id, nowFav)
            }
        )

        binding.recyclerSongs.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerSongs.adapter = adapter

        binding.btnSearch.setOnClickListener { doSearch() }
        binding.editQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.refreshFavorites(requireContext())
    }

    private fun doSearch() {
        val q = binding.editQuery.text?.toString().orEmpty().trim()
        if (q.isBlank()) return

        binding.progress.isVisible = true
        binding.txtEmpty.isVisible = false

        viewLifecycleOwner.lifecycleScope.launchWhenStarted {
            try {
                val candidates = withContext(Dispatchers.IO) { repo.search(q) }
                val favIds = FavoritesStore.getAll(requireContext()).map { it.id }.toSet()

                val items = candidates.map { c ->
                    SongAdapter.Item(
                        song = c.song,
                        subtitle = "score ${(c.score * 100).toInt()}%",
                        isFavorite = favIds.contains(c.song.id)
                    )
                }

                adapter.submit(items)
                binding.txtEmpty.isVisible = items.isEmpty()
            } catch (e: Exception) {
                adapter.submit(emptyList())
                binding.txtEmpty.isVisible = true
            } finally {
                binding.progress.isVisible = false
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
