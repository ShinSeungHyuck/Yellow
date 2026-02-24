package com.example.yellow.ui.search

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.yellow.R
import com.example.yellow.data.FavoritesStore
import com.example.yellow.data.Song
import com.example.yellow.data.SongCatalogRepository
import com.example.yellow.data.SongSearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchFragment : Fragment(R.layout.fragment_search) {

    companion object {
        private const val TAG = "SearchFragment"
        private const val CATALOG_LIMIT = 500
        private const val CATALOG_PREVIEW_COUNT = 5
    }

    private lateinit var editQuery: EditText
    private lateinit var btnSearch: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var txtEmpty: TextView
    private lateinit var recycler: RecyclerView

    private lateinit var adapter: SongAdapter

    private val searchRepo = SongSearchRepository()
    private val catalogRepo = SongCatalogRepository()

    private var catalogSongs: List<Song> = emptyList()
    private var loadCatalogJob: Job? = null
    private var searchJob: Job? = null

    private enum class Mode { CATALOG, SEARCH }
    private var mode: Mode = Mode.CATALOG

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        editQuery = view.findViewById(R.id.editQuery)
        btnSearch = view.findViewById(R.id.btnSearch)
        progress = view.findViewById(R.id.progress)
        txtEmpty = view.findViewById(R.id.txtEmpty)
        recycler = view.findViewById(R.id.recyclerSongs)

        adapter = SongAdapter(
            onClick = { item -> openPiano(item.song) },
            onToggleFavorite = { item ->
                // 리스트에서 즐겨찾기 추가할 땐 keyOffset은 0으로 저장(연습 화면에서 바꾸면 저장됨)
                val nowFav = FavoritesStore.toggle(requireContext(), item.song, keyOffsetWhenAdd = 0)
                val key = if (nowFav) FavoritesStore.getKeyOffset(requireContext(), item.song.id) else 0
                adapter.updateFavorite(item.song.id, nowFav, keyOffset = key)
            }
        )

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        loadCatalog()

        btnSearch.setOnClickListener { doSearch() }

        editQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }

        editQuery.addTextChangedListener { text ->
            val q = text?.toString()?.trim().orEmpty()
            if (q.isEmpty() && mode == Mode.SEARCH) {
                showCatalog()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Practice에서 keyOffset 저장 후 돌아오면 배지 갱신 필요
        refreshBadgesOnly()
    }

    private fun loadFavoriteMap(): Map<String, Int> {
        return FavoritesStore.getAllEntries(requireContext())
            .associate { it.song.id to it.keyOffset }
    }

    private fun refreshBadgesOnly() {
        if (!this::adapter.isInitialized) return
        val favMap = loadFavoriteMap()
        val updated = adapter.currentList.map { item ->
            item.copy(
                isFavorite = favMap.containsKey(item.song.id),
                keyOffset = favMap[item.song.id] ?: 0
            )
        }
        adapter.submit(updated)
    }

    private fun loadCatalog() {
        loadCatalogJob?.cancel()
        loadCatalogJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    progress.visibility = View.VISIBLE
                    txtEmpty.visibility = View.GONE
                }

                val items = catalogRepo.fetchCatalog(CATALOG_LIMIT)
                val songs = items.map { it.toSong() }

                withContext(Dispatchers.Main) {
                    catalogSongs = songs
                    showCatalog()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Catalog load failed", e)
                withContext(Dispatchers.Main) {
                    progress.visibility = View.GONE
                    txtEmpty.visibility = View.VISIBLE
                    txtEmpty.text = "목록 로드 실패"
                    Toast.makeText(requireContext(), "목록 로드 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun SongCatalogRepository.CatalogItem.toSong(): Song {
        val id = Song.makeId(melodyUrl, midiUrl)
        return Song(
            id = id,
            title = title,
            melodyUrl = melodyUrl,
            midiUrl = midiUrl,
            queryTitle = title
        )
    }

    private fun showCatalog() {
        mode = Mode.CATALOG
        progress.visibility = View.GONE

        if (catalogSongs.isEmpty()) {
            txtEmpty.visibility = View.VISIBLE
            txtEmpty.text = "목록이 비어있습니다."
            adapter.submit(emptyList())
            return
        }

        txtEmpty.visibility = View.GONE

        val preview = catalogSongs.shuffled().take(CATALOG_PREVIEW_COUNT)

        val favMap = loadFavoriteMap()

        val adapterItems = preview.map { s ->
            val (mainTitle, artist) = splitTitleArtist(s.title)
            val displaySong = s.copy(title = mainTitle, queryTitle = s.queryTitle)
            SongAdapter.Item(
                song = displaySong,
                subtitle = artist,
                isFavorite = favMap.containsKey(s.id),
                keyOffset = favMap[s.id] ?: 0
            )
        }

        adapter.submit(adapterItems)
    }

    private fun doSearch() {
        val q = editQuery.text?.toString()?.trim().orEmpty()
        if (q.isBlank()) {
            showCatalog()
            return
        }

        searchJob?.cancel()
        searchJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    mode = Mode.SEARCH
                    progress.visibility = View.VISIBLE
                    txtEmpty.visibility = View.GONE
                }

                val results = searchRepo.search(q)
                val favMap = loadFavoriteMap()

                val adapterItems = results.map { c ->
                    val fullTitle = c.song.title
                    val (mainTitle, artist) = splitTitleArtist(fullTitle)
                    val displaySong = c.song.copy(title = mainTitle)
                    SongAdapter.Item(
                        song = displaySong,
                        subtitle = artist,
                        isFavorite = favMap.containsKey(c.song.id),
                        keyOffset = favMap[c.song.id] ?: 0
                    )
                }

                withContext(Dispatchers.Main) {
                    progress.visibility = View.GONE
                    if (adapterItems.isEmpty()) {
                        txtEmpty.visibility = View.VISIBLE
                        txtEmpty.text = "검색 결과가 없습니다."
                    } else {
                        txtEmpty.visibility = View.GONE
                    }
                    adapter.submit(adapterItems)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Search failed", e)
                withContext(Dispatchers.Main) {
                    progress.visibility = View.GONE
                    Toast.makeText(requireContext(), "검색 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openPiano(song: Song) {
        val nav = findNavController()
        val destId = nav.currentDestination?.id

        val args = bundleOf(
            "title" to song.title,
            "melodyUrl" to song.melodyUrl,
            "midiUrl" to song.midiUrl,
            "queryTitle" to song.queryTitle
        )

        val actionId = when (destId) {
            R.id.libraryFragment -> R.id.action_libraryFragment_to_pianoFragment
            else -> R.id.action_searchFragment_to_pianoFragment
        }

        nav.navigate(actionId, args)
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
        loadCatalogJob?.cancel()
        searchJob?.cancel()
    }
}