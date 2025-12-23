package com.example.yellow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import com.google.android.exoplayer2.util.MimeTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.pow

class PianoFragment : Fragment() {

    private var _binding: FragmentPianoBinding? = null
    private val binding get() = _binding!!

    private var dispatcher: AudioDispatcher? = null
    private val PERMISSION_REQUEST_CODE = 124

    // ExoPlayer
    private var player: ExoPlayer? = null

    // 키 조절 상태 (반음 단위, -6 ~ +6)
    private var currentSemitoneOffset: Int = 0
    private val MIN_SEMITONES = -6
    private val MAX_SEMITONES = 6

    // 곡 로드 여부
    private var hasLoadedSong: Boolean = false

    // Cloudflare Worker 엔드포인트
    private val workerBaseUrl = "https://r2-music-search.den1116.workers.dev"

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

        // 기본 상태: 곡 없음
        binding.currentSongText.text = "현재 곡: 없음"

        // 키 SeekBar 설정 (-6 ~ +6 을 0~12 범위로 매핑)
        binding.keySeekBar.max = MAX_SEMITONES - MIN_SEMITONES // 12
        binding.keySeekBar.progress = currentSemitoneOffset - MIN_SEMITONES
        updateKeyOffsetText()

        binding.keySeekBar.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: android.widget.SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    currentSemitoneOffset = MIN_SEMITONES + progress
                    updatePlayerPitch()
                    updateKeyOffsetText()
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            }
        )

        // 검색 버튼: 곡 검색 + MIDI/MP4 로드
        binding.searchButton.setOnClickListener {
            val query = binding.songQueryInput.text.toString().trim()
            if (query.isEmpty()) {
                Toast.makeText(requireContext(), "곡 제목을 입력해 주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            searchAndLoadSong(query)
        }

        // 시작 버튼: 녹음 + 재생 동시에 시작
        binding.startButton.setOnClickListener {
            if (!hasLoadedSong || player == null) {
                Toast.makeText(requireContext(), "먼저 곡을 검색해서 선택해 주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!checkPermissions()) {
                requestPermissions()
                return@setOnClickListener
            }

            startPitchDetection()
            player?.playWhenReady = true
        }

        // 정지 버튼: 녹음/재생만 멈춤 (위치 유지)
        binding.stopButton.setOnClickListener {
            stopPitchDetection()
            player?.pause()
        }

        // 초기화 버튼: 화면에 보이는 '초기화'(stop_song_button) + 숨겨진 resetButton 둘 다 같은 동작
        binding.resetButton.setOnClickListener { resetPlaybackAndKey() }
        binding.stopSongButton.setOnClickListener { resetPlaybackAndKey() }
    }

    /**
     * 재생/녹음/키 상태 초기화 공통 함수
     */
    private fun resetPlaybackAndKey() {
        stopPitchDetection()
        player?.let {
            it.pause()
            it.seekTo(0)
        }
        currentSemitoneOffset = 0
        binding.keySeekBar.progress = currentSemitoneOffset - MIN_SEMITONES
        updatePlayerPitch()
        updateKeyOffsetText()
        binding.pianoRollView.clearLivePitches()
        // MIDI 악보는 유지
    }

    /**
     * Worker 를 이용해서 melody/midi 둘 다 검색 후
     * 첫 번째 매치를 사용해 MP4 재생 + MIDI 피아노롤 로드
     */
    private fun searchAndLoadSong(query: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")

                // Worker 쿼리 파라미터: query 사용
                val melodyUrl =
                    "$workerBaseUrl/search?bucket=melody&q=$encoded"
                val midiUrl =
                    "$workerBaseUrl/search?bucket=midi&q=$encoded"

                Log.d("PianoFragment", "Melody search URL = $melodyUrl")
                Log.d("PianoFragment", "MIDI search URL = $midiUrl")

                val melodyJsonText = getStringFromUrl(melodyUrl)
                val midiJsonText = getStringFromUrl(midiUrl)

                Log.d("PianoFragment", "Melody JSON = $melodyJsonText")
                Log.d("PianoFragment", "MIDI JSON = $midiJsonText")

                val melodyObj = JSONObject(melodyJsonText)
                val midiObj = JSONObject(midiJsonText)

                // matches 또는 results 둘 다 대응
                val melodyMatches = melodyObj.optJSONArray("matches")
                    ?: melodyObj.optJSONArray("results")
                val midiMatches = midiObj.optJSONArray("matches")
                    ?: midiObj.optJSONArray("results")

                if (melodyMatches == null || melodyMatches.length() == 0 ||
                    midiMatches == null || midiMatches.length() == 0
                ) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            requireContext(),
                            "검색 결과가 없습니다.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                val melodyFirst = melodyMatches.getJSONObject(0)
                val midiFirst = midiMatches.getJSONObject(0)

                val melodyFileName = melodyFirst.optString("key")
                val melodyFileUrl = melodyFirst.optString("url")
                val midiFileUrl = midiFirst.optString("url")

                Log.d("PianoFragment", "Selected melody = $melodyFileName")
                Log.d("PianoFragment", "Melody URL = $melodyFileUrl")
                Log.d("PianoFragment", "MIDI URL = $midiFileUrl")

                withContext(Dispatchers.Main) {
                    // 곡 정보 표시
                    binding.currentSongText.text =
                        "현재 곡: ${melodyFileName.ifEmpty { query }}"

                    // 플레이어 준비
                    preparePlayer(melodyFileUrl)
                    hasLoadedSong = true

                    // MIDI 로드
                    loadMidiFromUrl(midiFileUrl)
                }
            } catch (e: Exception) {
                Log.e("PianoFragment", "Failed to search/load song", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "곡 검색/로딩 중 오류가 발생했습니다.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * 간단한 HTTP GET 헬퍼 (상태코드 로그용)
     */
    private fun getStringFromUrl(urlString: String): String {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
        }

        return try {
            val code = conn.responseCode
            Log.d("PianoFragment", "HTTP GET $urlString -> $code")

            val stream = if (code in 200..299) {
                conn.inputStream
            } else {
                conn.errorStream ?: conn.inputStream
            }

            stream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * ExoPlayer 준비 (MP4 컨테이너 명시)
     */
    private fun preparePlayer(url: String) {
        player?.release()

        Log.d("PianoFragment", "preparePlayer url = $url")

        val exoPlayer = ExoPlayer.Builder(requireContext()).build()
        player = exoPlayer

        // 핵심: MP4로 MIME 타입을 명시해 추출기/포맷 오인 문제를 줄임
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMimeType(MimeTypes.APPLICATION_MP4) // 일반 mp4(컨테이너)
            // 오디오 전용(mp4 컨테이너, 사실상 m4a 성격)이라면 아래로 바꿔도 됩니다.
            // .setMimeType(MimeTypes.AUDIO_MP4)
            .build()

        exoPlayer.setMediaItem(mediaItem)

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e("PianoFragment", "ExoPlayer error: ${error.errorCodeName}", error)
                Toast.makeText(
                    requireContext(),
                    "오디오 재생 중 오류가 발생했습니다.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        // 현재 키 상태 반영
        updatePlayerPitch()

        exoPlayer.prepare()
        exoPlayer.playWhenReady = false
    }

    /**
     * 반음 단위 오프셋을 ExoPlayer 피치에 적용
     */
    private fun updatePlayerPitch() {
        val p = player ?: return

        // 반음 → 배율 : 2^(n/12)
        val pitchFactor = 2.0.pow(currentSemitoneOffset / 12.0).toFloat()

        val currentParams = p.playbackParameters
        val newParams = PlaybackParameters(
            currentParams.speed, // 속도는 그대로
            pitchFactor          // 피치만 변경
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

    // ======= 피치 인식 관련 =======

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

    // ======= 권한 =======

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
                if (hasLoadedSong && player != null) {
                    startPitchDetection()
                    player?.playWhenReady = true
                }
            }
        }
    }

    // ======= MIDI 로딩 (URL 이용) =======

    private fun loadMidiFromUrl(url: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("PianoFragment", "Attempting to download MIDI file from URL: $url")
                val inputStream = URL(url).openStream()
                val midiParser = MidiParser()
                val notes = midiParser.parse(inputStream)

                withContext(Dispatchers.Main) {
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
                withContext(Dispatchers.Main) {
                    binding.pianoRollView.setNotes(emptyList())
                    binding.pitchView.setPitchRange(48, 72)
                }
            }
        }
    }

    // (선택) 로컬 assets 에서 MIDI 로드 – 필요 시 사용
    private fun loadMidiFileFromAssets(fileName: String) {
        try {
            Log.d("PianoFragment", "Attempting to load MIDI file: $fileName")
            val inputStream = requireContext().assets.open(fileName)
            val midiParser = MidiParser()
            val notes = midiParser.parse(inputStream)

            if (notes.isEmpty()) {
                Log.w(
                    "PianoFragment",
                    "MIDI parsing resulted in 0 notes. Check the MIDI file or parser logic."
                )
            } else {
                Log.d("PianoFragment", "Successfully parsed ${notes.size} notes.")
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

    override fun onDestroyView() {
        super.onDestroyView()
        stopPitchDetection()
        player?.release()
        player = null
        _binding = null
    }
}
