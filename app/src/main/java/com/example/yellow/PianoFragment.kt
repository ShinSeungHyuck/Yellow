package com.example.yellow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.io.android.AudioDispatcherFactory
import be.tarsos.dsp.pitch.PitchDetectionHandler
import be.tarsos.dsp.pitch.PitchProcessor
import com.example.yellow.databinding.FragmentPianoBinding
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.pow

class PianoFragment : Fragment() {

    // Cloudflare Worker 검색 API 기본 URL
    private val SEARCH_API_BASE = "https://r2-music-search.den1116.workers.dev/search"

    // 검색 결과용 데이터 클래스 (Worker 응답 matches 구조와 맞춤)
    data class SearchMatch(val key: String, val size: Long, val url: String)

    private var _binding: FragmentPianoBinding? = null
    private val binding get() = _binding!!

    private var dispatcher: AudioDispatcher? = null
    private val PERMISSION_REQUEST_CODE = 124

    // ExoPlayer
    private var player: ExoPlayer? = null

    // 키 조절 상태 (반음 단위, 예: -6 ~ +6)
    private var currentSemitoneOffset: Int = 0
    private val MIN_SEMITONES = -6
    private val MAX_SEMITONES = 6

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPianoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 초기 키 표시
        updateKeyOffsetText()

        // ───────────── 재생 + 녹음/피치인식 버튼 ─────────────

        // 녹음 + 피치 인식 + 동시에 노래 재생
        binding.startButton.setOnClickListener {
            if (checkPermissions()) {
                startPitchDetection()
                player?.playWhenReady = true
            } else {
                requestPermissions()
            }
        }

        binding.stopButton.setOnClickListener {
            stopPitchDetection()
            player?.let {
                it.pause()
                it.seekTo(0)
            }
        }

        // 노래만 컨트롤하는 버튼 (Play / Pause / Stop)
        binding.playButton.setOnClickListener {
            val p = player
            if (p == null) {
                Log.w("PianoFragment", "Player is null. Search and prepare first.")
                return@setOnClickListener
            }
            p.playWhenReady = true   // 준비 중이면 준비 완료 후 자동 재생
        }

        binding.pauseButton.setOnClickListener {
            player?.pause()
        }

        binding.stopSongButton.setOnClickListener {
            player?.let {
                it.pause()
                it.seekTo(0)
            }
        }

        // ───────────── 키(반음) 조절 버튼 ─────────────

        binding.keyUpButton.setOnClickListener {
            if (currentSemitoneOffset < MAX_SEMITONES) {
                currentSemitoneOffset++
                updatePlayerPitch()
                updateKeyOffsetText()
            }
        }

        binding.keyDownButton.setOnClickListener {
            if (currentSemitoneOffset > MIN_SEMITONES) {
                currentSemitoneOffset--
                updatePlayerPitch()
                updateKeyOffsetText()
            }
        }

        // ───────────── 곡 검색 버튼 ─────────────
        // 검색어(제목, 가수 일부 등) 입력 → Worker search → mp3 & MIDI 로드

        binding.songSearchButton.setOnClickListener {
            val query = binding.songSearchInput.text.toString().trim()
            if (query.isNotEmpty()) {
                searchAndLoadSong(query)
            } else {
                Log.w("PianoFragment", "Search query is empty.")
            }
        }
    }

    // =========================================================
    // ExoPlayer 관련
    // =========================================================

    /**
     * ExoPlayer 준비 (주어진 URL의 mp3를 재생할 준비를 함)
     */
    private fun preparePlayer(url: String) {
        player?.release()

        Log.d("PianoFragment", "preparePlayer url = $url")

        val exoPlayer = ExoPlayer.Builder(requireContext()).build()
        player = exoPlayer

        val mediaItem = MediaItem.fromUri(url)
        exoPlayer.setMediaItem(mediaItem)

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e("PianoFragment", "ExoPlayer error: ${error.errorCodeName}", error)
            }
        })

        // 현재 반음 offset 기준으로 피치 적용
        updatePlayerPitch()

        exoPlayer.prepare()
        exoPlayer.playWhenReady = false
    }

    /**
     * 반음 단위 오프셋을 ExoPlayer 피치로 적용
     *  - 반음 n 만큼 이동 시 배율 = 2^(n/12)
     *  - 속도(speed)는 1.0 그대로, pitch만 조정하여 키만 변화시키고 템포는 유지
     */
    private fun updatePlayerPitch() {
        val p = player ?: return

        val pitchFactor = 2.0.pow(currentSemitoneOffset / 12.0).toFloat()
        val currentParams = p.playbackParameters
        val newParams = PlaybackParameters(
            /* speed = */ currentParams.speed,
            /* pitch = */ pitchFactor
        )
        p.playbackParameters = newParams

        Log.d(
            "PianoFragment",
            "Pitch updated. semitones=$currentSemitoneOffset, factor=$pitchFactor"
        )
    }

    /**
     * 키 표시 텍스트 업데이트
     */
    private fun updateKeyOffsetText() {
        val text = when {
            currentSemitoneOffset > 0 -> "Key: +$currentSemitoneOffset"
            currentSemitoneOffset < 0 -> "Key: $currentSemitoneOffset"
            else -> "Key: 0 (원조)"
        }
        binding.keyOffsetText.text = text
    }

    // =========================================================
    // Cloudflare Worker 검색 호출
    // =========================================================

    private fun searchAndLoadSong(query: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")

                val melodyApiUrl = "$SEARCH_API_BASE?bucket=melody&q=$encoded"
                val midiApiUrl = "$SEARCH_API_BASE?bucket=midi&q=$encoded"

                // 멜로디 검색
                val melodyMatches = fetchMatches(melodyApiUrl)
                val melodyUrl = melodyMatches.firstOrNull()?.url

                // 미디 검색
                val midiMatches = fetchMatches(midiApiUrl)
                val midiUrl = midiMatches.firstOrNull()?.url

                Log.d(
                    "PianoFragment",
                    "Search result -> melodyUrl=$melodyUrl, midiUrl=$midiUrl"
                )

                activity?.runOnUiThread {
                    // mp3 교체
                    melodyUrl?.let { url ->
                        preparePlayer(url)
                    }

                    // midi 교체
                    midiUrl?.let { url ->
                        loadMidiFromUrl(url)
                    }
                }
            } catch (e: Exception) {
                Log.e("PianoFragment", "Failed to call search API.", e)
            }
        }
    }

    /**
     * 주어진 검색 API URL에서 JSON 응답 파싱 → List<SearchMatch>
     */
    private fun fetchMatches(apiUrl: String): List<SearchMatch> {
        return try {
            Log.d("PianoFragment", "Calling search API: $apiUrl")

            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            val code = conn.responseCode
            val stream =
                if (code in 200..299) conn.inputStream else conn.errorStream

            val body = stream?.use { it.readBytes() }?.toString(Charsets.UTF_8) ?: ""

            if (code !in 200..299) {
                Log.e("PianoFragment", "Search API error ($apiUrl): code=$code, body=$body")
                return emptyList()
            }

            val json = JSONObject(body)
            val matchesJson = json.optJSONArray("matches") ?: return emptyList()

            val result = mutableListOf<SearchMatch>()
            for (i in 0 until matchesJson.length()) {
                val obj = matchesJson.optJSONObject(i) ?: continue
                val key = obj.optString("key", "")
                val size = obj.optLong("size", 0L)
                val url = obj.optString("url", "")

                if (url.isNotBlank()) {
                    result.add(SearchMatch(key, size, url))
                }
            }
            result
        } catch (e: Exception) {
            Log.e("PianoFragment", "fetchMatches failed ($apiUrl)", e)
            emptyList()
        }
    }

    // =========================================================
    // 피치 인식(TarsosDSP) 관련
    // =========================================================

    private fun startPitchDetection() {
        if (dispatcher?.isStopped == false) {
            Log.d("PianoFragment", "Pitch detection is already running.")
            return
        }

        binding.pianoRollView.clearLivePitches()
        binding.pitchView.clearDetectedPitch()

        dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(22050, 1024, 0)

        val pitchDetectionHandler = PitchDetectionHandler { res, _ ->
            val pitchInHz = res.pitch
            activity?.runOnUiThread {
                if (pitchInHz > 0) {
                    binding.pitchView.setDetectedPitch(pitchInHz)
                    binding.pianoRollView.addLivePitch(pitchInHz)
                }
            }
        }

        val pitchProcessor = PitchProcessor(
            PitchProcessor.PitchEstimationAlgorithm.YIN,
            22050f,
            1024,
            pitchDetectionHandler
        )
        dispatcher?.addAudioProcessor(pitchProcessor)

        Thread(dispatcher, "Audio Dispatcher").start()
        Log.d("PianoFragment", "Started pitch detection.")
    }

    private fun stopPitchDetection() {
        dispatcher?.stop()
        binding.pitchView.clearDetectedPitch()
        binding.pianoRollView.clearLivePitches()
        Log.d("PianoFragment", "Stopped pitch detection.")
    }

    // =========================================================
    // 권한
    // =========================================================

    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            requireActivity(),
            arrayOf(Manifest.permission.RECORD_AUDIO),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startPitchDetection()
                player?.playWhenReady = true
            }
        }
    }

    // =========================================================
    // MIDI 로딩 (URL 이용)
    // =========================================================

    private fun loadMidiFromUrl(url: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("PianoFragment", "Attempting to download MIDI file from URL: $url")
                val midiParser = MidiParser()
                val notes = URL(url).openStream().use { input ->
                    midiParser.parse(input)
                }

                activity?.runOnUiThread {
                    if (notes.isEmpty()) {
                        Log.w(
                            "PianoFragment",
                            "MIDI parsing resulted in 0 notes. Check the MIDI file or parser logic."
                        )
                    } else {
                        Log.d(
                            "PianoFragment",
                            "Successfully parsed ${notes.size} notes from URL."
                        )
                    }

                    binding.pianoRollView.setNotes(notes)
                    binding.pitchView.setPitchRange(
                        binding.pianoRollView.currentMinPitch,
                        binding.pianoRollView.currentMaxPitch
                    )
                }
            } catch (e: Exception) {
                Log.e("PianoFragment", "Failed to load MIDI file from URL.", e)
                activity?.runOnUiThread {
                    binding.pianoRollView.setNotes(emptyList())
                    binding.pitchView.setPitchRange(48, 72)
                }
            }
        }
    }

    // (옵션) 로컬 assets 에서 MIDI 로드 – 필요 시 사용
    private fun loadMidiFileFromAssets(fileName: String) {
        try {
            Log.d("PianoFragment", "Attempting to load MIDI file from assets: $fileName")
            val inputStream = requireContext().assets.open(fileName)
            val midiParser = MidiParser()
            val notes = midiParser.parse(inputStream)

            if (notes.isEmpty()) {
                Log.w("PianoFragment", "MIDI parsing resulted in 0 notes. Check the MIDI file or parser logic.")
            } else {
                Log.d("PianoFragment", "Successfully parsed ${notes.size} notes from assets.")
            }

            binding.pianoRollView.setNotes(notes)
            binding.pitchView.setPitchRange(
                binding.pianoRollView.currentMinPitch,
                binding.pianoRollView.currentMaxPitch
            )
        } catch (e: IOException) {
            Log.e(
                "PianoFragment",
                "Failed to load MIDI file '$fileName' from assets. Make sure the file exists.",
                e
            )
            binding.pianoRollView.setNotes(emptyList())
            binding.pitchView.setPitchRange(48, 72)
        }
    }

    // =========================================================

    override fun onDestroyView() {
        super.onDestroyView()
        stopPitchDetection()
        player?.release()
        player = null
        _binding = null
    }
}
