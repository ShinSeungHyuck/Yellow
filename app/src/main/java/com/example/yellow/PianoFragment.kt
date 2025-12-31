package com.example.yellow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.log2
import kotlin.math.pow

class PianoFragment : Fragment(R.layout.fragment_piano) {

    // ===== UI =====
    private lateinit var songQueryInput: EditText
    private lateinit var searchButton: Button
    private lateinit var currentSongText: TextView

    private lateinit var keySeekBar: SeekBar
    private lateinit var keyOffsetText: TextView

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var stopSongButton: Button

    private var pianoRollView: PianoRollView? = null
    private var pitchView: PitchView? = null

    // ===== Player =====
    private var player: ExoPlayer? = null
    private var currentMelodyUrl: String? = null
    private var currentSongTitle: String? = null

    private var currentSemitones: Int = 0

    // ===== MIDI =====
    private var originalMidiNotes: List<MusicalNote> = emptyList()
    private var fixedMinPitch: Int? = null
    private var fixedMaxPitch: Int? = null

    private var searchJob: Job? = null
    private var positionLogJob: Job? = null

    // ===== Voice detector =====
    private var voiceDetector: VoicePitchDetector? = null

    // ★★★★★ 핵심 수정: ExoPlayer는 메인 스레드 접근만 허용
    // 따라서 메인에서 주기적으로 currentPosition을 캐시해두고,
    // 백그라운드(VoicePitchDetector thread)에서는 이 값을 읽기만 한다.
    @Volatile private var lastSafePlayerPositionMs: Long = 0L
    private var positionSamplerJob: Job? = null

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(requireContext(), "마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        } else {
            startVoiceDetectorIfNeeded()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        songQueryInput = view.findViewById(R.id.songQueryInput)
        searchButton = view.findViewById(R.id.searchButton)
        currentSongText = view.findViewById(R.id.currentSongText)

        keySeekBar = view.findViewById(R.id.keySeekBar)
        keyOffsetText = view.findViewById(R.id.key_offset_text)

        startButton = view.findViewById(R.id.start_button)
        stopButton = view.findViewById(R.id.stop_button)
        stopSongButton = view.findViewById(R.id.stop_song_button)

        pianoRollView = view.findViewById(R.id.piano_roll_view)
        pitchView = view.findViewById(R.id.pitch_view)

        startButton.isEnabled = false
        stopButton.isEnabled = false
        stopSongButton.isEnabled = false

        keySeekBar.max = 24
        keySeekBar.progress = 12
        currentSemitones = 0
        updateKeyText()

        keySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentSemitones = progress - 12
                updateKeyText()
                updatePlayerPitch()
                applyMidiTransposeToView() // live 타일 유지(resetLivePitches=false)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        searchButton.setOnClickListener {
            val q = songQueryInput.text?.toString()?.trim().orEmpty()
            if (q.isEmpty()) {
                Toast.makeText(requireContext(), "검색어를 입력하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            searchAndLoad(q)
        }

        // play
        startButton.setOnClickListener {
            val p = player
            if (p == null) {
                Toast.makeText(requireContext(), "먼저 노래를 검색해 주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            ensureMicPermissionAndStartDetector()

            p.playWhenReady = true
            p.play()
            logPlayerSnapshot("after play()")
            startPositionLogger()

            // ★ 캐시 업데이트 시작(메인 스레드)
            startPositionSampler()
        }

        // pause
        stopButton.setOnClickListener {
            player?.pause()
            logPlayerSnapshot("after pause()")
            // 필요 시 pause에서 detector stop 가능. (지금은 유지)
        }

        // reset
        stopSongButton.setOnClickListener {
            val p = player ?: return@setOnClickListener
            p.pause()
            p.seekTo(0)
            lastSafePlayerPositionMs = 0L
            logPlayerSnapshot("after reset()")

            // 요구사항: 초기화 시 사용자 녹음표시 전부 삭제 + 0초부터 다시
            pianoRollView?.clearLivePitches()
            pitchView?.clearDetectedPitch()
        }
    }

    private fun ensureMicPermissionAndStartDetector() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startVoiceDetectorIfNeeded()
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceDetectorIfNeeded() {
        if (voiceDetector != null) return

        // ★ 메인 스레드에서 position 캐시를 꾸준히 갱신
        startPositionSampler()

        voiceDetector = VoicePitchDetector { hz, _ ->
            // ★ 여기(백그라운드 스레드)에서 player 접근 금지!
            val timeMs = lastSafePlayerPositionMs

            val midi = if (hz > 0f) {
                (69.0 + 12.0 * log2(hz.toDouble() / 440.0)).toFloat()
            } else 0f

            // UI 반영은 메인으로
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                pitchView?.setDetectedPitch(midi)
                pianoRollView?.addLivePitchAt(timeMs, midi)
            }
        }

        voiceDetector?.start()
        Log.d("PianoFragment", "VoicePitchDetector started")
    }

    private fun stopVoiceDetector() {
        voiceDetector?.stop()
        voiceDetector = null
        Log.d("PianoFragment", "VoicePitchDetector stopped")
    }

    /**
     * ★ ExoPlayer currentPosition을 "메인 스레드에서만" 읽어 캐시
     * 50ms 정도면 충분히 촘촘하게 타일이 찍힘.
     */
    private fun startPositionSampler() {
        if (positionSamplerJob != null) return
        positionSamplerJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            while (true) {
                val p = player
                if (p != null) {
                    lastSafePlayerPositionMs = p.currentPosition
                }
                delay(50)
            }
        }
    }

    private fun stopPositionSampler() {
        positionSamplerJob?.cancel()
        positionSamplerJob = null
    }

    private fun updateKeyText() {
        val label = when {
            currentSemitones == 0 -> "Key: 0 (원조)"
            currentSemitones > 0 -> "Key: +$currentSemitones"
            else -> "Key: $currentSemitones"
        }
        keyOffsetText.text = label
    }

    private fun searchAndLoad(query: String) {
        searchJob?.cancel()
        searchJob = null

        searchButton.isEnabled = false
        startButton.isEnabled = false
        stopButton.isEnabled = false
        stopSongButton.isEnabled = false

        originalMidiNotes = emptyList()
        fixedMinPitch = null
        fixedMaxPitch = null

        pianoRollView?.setNotes(emptyList())
        pianoRollView?.clearLivePitches()
        pitchView?.clearDetectedPitch()

        lastSafePlayerPositionMs = 0L

        searchJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")

                val melodySearchUrl =
                    "https://r2-music-search.den1116.workers.dev/search?bucket=melody&q=$encoded"
                val midiSearchUrl =
                    "https://r2-music-search.den1116.workers.dev/search?bucket=midi&q=$encoded"

                Log.d("PianoFragment", "Melody search URL = $melodySearchUrl")
                Log.d("PianoFragment", "MIDI search URL = $midiSearchUrl")

                val melodyJson = httpGetText(melodySearchUrl)
                val midiJson = httpGetText(midiSearchUrl)

                val (melodyUrl, melodyKey) = selectFirstMatchUrlAndKey(melodyJson)
                val (midiUrl, _) = selectFirstMatchUrlAndKey(midiJson)

                if (melodyUrl.isNullOrBlank() || midiUrl.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "검색 결과가 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    currentMelodyUrl = melodyUrl
                    currentSongTitle = melodyKey ?: query
                    currentSongText.text = "현재 곡: ${currentSongTitle ?: "없음"}"

                    preparePlayer(melodyUrl)

                    startButton.isEnabled = true
                    stopButton.isEnabled = true
                    stopSongButton.isEnabled = true
                }

                downloadAndApplyMidi(midiUrl)

            } catch (e: Exception) {
                Log.e("PianoFragment", "searchAndLoad failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "오류: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    searchButton.isEnabled = true
                }
            }
        }
    }

    private fun preparePlayer(url: String) {
        releasePlayer()

        Log.d("PianoFragment", "preparePlayer url = $url")

        val exoPlayer = ExoPlayer.Builder(requireContext()).build()
        player = exoPlayer

        val attrs = AudioAttributes.Builder()
            .setUsage(com.google.android.exoplayer2.C.USAGE_MEDIA)
            .setContentType(com.google.android.exoplayer2.C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        exoPlayer.setAudioAttributes(attrs, true)

        exoPlayer.setHandleAudioBecomingNoisy(true)
        exoPlayer.volume = 1.0f

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e("PianoFragment", "ExoPlayer error: ${error.errorCodeName}", error)
                Toast.makeText(requireContext(), "오디오 재생 오류: ${error.errorCodeName}", Toast.LENGTH_SHORT).show()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(
                    "PianoFragment",
                    "state=${stateName(playbackState)} playWhenReady=${exoPlayer.playWhenReady} isPlaying=${exoPlayer.isPlaying} pos=${exoPlayer.currentPosition}"
                )
                // state 변할 때도 캐시 한번 갱신
                lastSafePlayerPositionMs = exoPlayer.currentPosition
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d("PianoFragment", "onIsPlayingChanged=$isPlaying pos=${exoPlayer.currentPosition}")
                lastSafePlayerPositionMs = exoPlayer.currentPosition
            }
        })

        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        updatePlayerPitch()

        exoPlayer.prepare()
        exoPlayer.playWhenReady = false

        lastSafePlayerPositionMs = 0L
        logPlayerSnapshot("after prepare()")
    }

    private fun updatePlayerPitch() {
        val p = player ?: return
        val factor = 2.0.pow(currentSemitones / 12.0).toFloat()
        p.playbackParameters = PlaybackParameters(1.0f, factor)
        Log.d("PianoFragment", "Pitch updated. semitones=$currentSemitones, factor=$factor")
    }

    private fun downloadAndApplyMidi(midiUrl: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("PianoFragment", "Attempting to download MIDI file from URL: $midiUrl")
                val bytes = httpGetBytes(midiUrl)

                val inputStream = ByteArrayInputStream(bytes)
                val parser = MidiParser()
                val notes: List<MusicalNote> = parser.parse(inputStream)

                withContext(Dispatchers.Main) {
                    originalMidiNotes = notes

                    if (originalMidiNotes.isNotEmpty()) {
                        fixedMinPitch = (originalMidiNotes.minOf { it.note } - 4).coerceAtLeast(0)
                        fixedMaxPitch = (originalMidiNotes.maxOf { it.note } + 4).coerceAtMost(83)
                    } else {
                        fixedMinPitch = null
                        fixedMaxPitch = null
                    }

                    applyMidiTransposeToView()

                    val fMin = fixedMinPitch
                    val fMax = fixedMaxPitch
                    if (fMin != null && fMax != null) {
                        pitchView?.setPitchRange(fMin, fMax)
                    }

                    Log.d(
                        "PianoFragment",
                        "MIDI loaded. originalCount=${originalMidiNotes.size} fixedRange=${fixedMinPitch}-${fixedMaxPitch}"
                    )
                }
            } catch (e: Exception) {
                Log.e("PianoFragment", "MIDI download/parse failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "MIDI 처리 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun applyMidiTransposeToView() {
        val view = pianoRollView ?: return
        if (originalMidiNotes.isEmpty()) return

        val transposed = transposeNotes(originalMidiNotes, currentSemitones)

        val fMin = fixedMinPitch
        val fMax = fixedMaxPitch
        if (fMin != null && fMax != null) {
            view.setNotes(transposed, fMin, fMax, resetLivePitches = false)
        } else {
            view.setNotes(transposed)
        }

        Log.d("PianoFragment", "Applied MIDI transpose semitones=$currentSemitones")
    }

    private fun transposeNotes(notes: List<MusicalNote>, semitones: Int): List<MusicalNote> {
        if (semitones == 0) return notes
        return notes.map { n ->
            val newNoteNumber = (n.note + semitones).coerceIn(0, 127)
            MusicalNote(newNoteNumber, n.startTime, n.duration)
        }
    }

    private fun startPositionLogger() {
        positionLogJob?.cancel()
        val p = player ?: return
        positionLogJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            val startTs = SystemClock.elapsedRealtime()
            while (true) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                Log.d(
                    "PianoFragment",
                    "posLog t=${(now - startTs) / 1000}s state=${stateName(p.playbackState)} isPlaying=${p.isPlaying} pos=${p.currentPosition} vol=${p.volume}"
                )
                lastSafePlayerPositionMs = p.currentPosition
            }
        }
    }

    private fun releasePlayer() {
        positionLogJob?.cancel()
        positionLogJob = null

        player?.release()
        player = null
        lastSafePlayerPositionMs = 0L
    }

    private fun httpGetText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            Log.d("PianoFragment", "HTTP GET $url -> $code")
            if (code !in 200..299) throw RuntimeException("HTTP $code: $body")
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGetBytes(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 20_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream.use { it.readBytes() }
            Log.d("PianoFragment", "HTTP GET(bytes) $url -> $code size=${bytes.size}")
            if (code !in 200..299) throw RuntimeException("HTTP $code (bytes)")
            return bytes
        } finally {
            conn.disconnect()
        }
    }

    private fun selectFirstMatchUrlAndKey(json: String): Pair<String?, String?> {
        val obj = JSONObject(json)
        val matches = obj.optJSONArray("matches") ?: return null to null
        if (matches.length() == 0) return null to null
        val first = matches.getJSONObject(0)
        val url = first.optString("url", null)
        val key = first.optString("key", null)
        return url to key
    }

    private fun logPlayerSnapshot(tag: String) {
        val p = player ?: run {
            Log.d("PianoFragment", "[$tag] player=null")
            return
        }
        Log.d(
            "PianoFragment",
            "[$tag] state=${stateName(p.playbackState)} playWhenReady=${p.playWhenReady} isPlaying=${p.isPlaying} vol=${p.volume} pos=${p.currentPosition} dur=${p.duration}"
        )
        lastSafePlayerPositionMs = p.currentPosition
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        stopVoiceDetector()
        stopPositionSampler()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        searchJob?.cancel()
        searchJob = null

        stopVoiceDetector()
        stopPositionSampler()
        releasePlayer()

        pianoRollView = null
        pitchView = null
    }
}
