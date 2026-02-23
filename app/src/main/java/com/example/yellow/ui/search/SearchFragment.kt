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
                val nowFav = FavoritesStore.toggle(requireContext(), item.song)
                adapter.updateFavorite(item.song.id, nowFav)
            }
        )

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        // 1) 화면 들어오면 "전체목록" 먼저 로드해서 보여주기
        loadCatalog()

        // 2) 검색 버튼
        btnSearch.setOnClickListener { doSearch() }

        // 3) 키보드 검색 버튼
        editQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }

        // 4) 검색어 비우면 자동으로 전체목록으로 복귀
        editQuery.addTextChangedListener { text ->
            val q = text?.toString()?.trim().orEmpty()
            if (q.isEmpty() && mode == Mode.SEARCH) {
                showCatalog()
            }
        }
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

                // title: "곡명 - 가수" 형태면 subtitle로 가수 표시
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
        // queryTitle은 가사 검색에 쓰이니까 "곡명 - 가수" 전체를 넣는게 유리
        return Song(
            id = id,
            title = title,          // 리스트에도 그대로 표시
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

        // ✅ 여기서 5개를 랜덤으로 뽑음
        val preview = catalogSongs.shuffled().take(CATALOG_PREVIEW_COUNT)

        val favSet = FavoritesStore.getAll(requireContext()).map { it.id }.toSet()

        val adapterItems = preview.map { s ->
            val (mainTitle, artist) = splitTitleArtist(s.title)
            val displaySong = s.copy(title = mainTitle, queryTitle = s.queryTitle)
            SongAdapter.Item(
                song = displaySong,
                subtitle = artist,
                isFavorite = favSet.contains(s.id)
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

                val results = searchRepo.search(q) // List<Candidate(song, score)>
                val favSet = FavoritesStore.getAll(requireContext()).map { it.id }.toSet()

                val adapterItems = results.map { c ->
                    val fullTitle = c.song.title
                    val (mainTitle, artist) = splitTitleArtist(fullTitle)
                    val displaySong = c.song.copy(title = mainTitle) // 리스트에선 제목만 크게
                    SongAdapter.Item(
                        song = displaySong,
                        subtitle = artist,
                        isFavorite = favSet.contains(c.song.id)
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

        // SearchFragment가 libraryFragment로도 재사용될 수 있으니 안전하게 분기
        val actionId = when (destId) {
            R.id.libraryFragment -> R.id.action_libraryFragment_to_pianoFragment
            else -> R.id.action_searchFragment_to_pianoFragment
        }

        nav.navigate(actionId, args)
    }

    private fun splitTitleArtist(full: String): Pair<String, String?> {
        // "곡명 - 가수" 패턴만 처리
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
