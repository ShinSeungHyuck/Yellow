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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.log2
import kotlin.math.pow

class PianoFragment : Fragment(R.layout.fragment_piano) {

    companion object {
        private const val TAG = "PianoFragment"

        // ===== 사용자가 쉽게 초를 바꿀 수 있도록 상단 상수화 =====
        private const val MP3_ONSET_WINDOW_SEC = 4.0      // Rule1 기준
        private const val MIDI_EARLY_START_SEC = 3.0      // Rule2 판정용 (MIDI 첫 타일)

        private const val MIDI_DELAY_RULE1_SEC = 4.7      // Rule1: mp3 확실한 음 4초 안에 시작 X
        private const val MIDI_DELAY_RULE2_SEC = 2.7      // Rule2: (Rule1 아님) + MIDI 첫 타일 3초 안
        private const val MIDI_DELAY_RULE3_SEC = 0.8      // Rule3: 나머지

        // ExoPlayer currentPosition 캐시 주기
        private const val POSITION_SAMPLE_MS = 50L
    }

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

    private var isMidiEarlyStart: Boolean = false

    // 최종 확정된 MIDI 딜레이(ms) — “검색 확정 단계”에서 결정됨
    private var midiDelayMs: Long = (MIDI_DELAY_RULE3_SEC * 1000).toLong()

    private var searchJob: Job? = null
    private var positionLogJob: Job? = null

    // ===== Voice detector (마이크) =====
    private var voiceDetector: VoicePitchDetector? = null

    // ExoPlayer 메인 스레드 currentPosition 캐시
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
                applyMidiTransposeAndDelayToView(resetLive = false)
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

            // 딜레이는 이미 검색 단계에서 확정되어 있음
            ensureMicPermissionAndStartDetector()

            p.playWhenReady = true
            p.play()

            startPositionSampler()
            startPositionLogger()
        }

        // pause
        stopButton.setOnClickListener {
            player?.pause()
        }

        // reset
        stopSongButton.setOnClickListener {
            val p = player ?: return@setOnClickListener
            p.pause()
            p.seekTo(0)
            lastSafePlayerPositionMs = 0L

            pianoRollView?.clearLivePitches()
            pitchView?.clearDetectedPitch()
        }
    }

    // ===== 핵심: 검색/확정 단계에서 MIDI 딜레이 확정 =====
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
        isMidiEarlyStart = false
        midiDelayMs = (MIDI_DELAY_RULE3_SEC * 1000).toLong()

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

                // 1) MP3 onset 판정(0~4초 내 확실한 음 시작?)과
                // 2) MIDI 파싱(첫 타일 3초 내 시작?)을 병렬로 수행
                val mp3OnsetDeferred = async(Dispatchers.IO) {
                    detectMp3OnsetWithinWindowWithDetector(melodyUrl, MP3_ONSET_WINDOW_SEC)
                }

                val midiNotes = downloadAndParseMidi(midiUrl)
                val mp3HasOnsetIn4s = mp3OnsetDeferred.await()

                // MIDI early-start 계산
                val midiEarly = if (midiNotes.isNotEmpty()) {
                    val firstStartMs = midiNotes.minOf { it.startTime }
                    firstStartMs <= (MIDI_EARLY_START_SEC * 1000).toLong()
                } else false

                // 규칙에 따라 “검색 단계에서” 딜레이 확정
                val decidedDelaySec = decideMidiDelaySeconds(
                    mp3HasOnsetIn4s = mp3HasOnsetIn4s,
                    midiEarlyStart = midiEarly
                )

                withContext(Dispatchers.Main) {
                    currentMelodyUrl = melodyUrl
                    currentSongTitle = melodyKey ?: query
                    currentSongText.text = "현재 곡: ${currentSongTitle ?: "없음"}"

                    // ExoPlayer 준비는 유지(사용자 재생 대기)
                    preparePlayer(melodyUrl)

                    // MIDI 상태 저장 + 범위 계산
                    originalMidiNotes = midiNotes
                    isMidiEarlyStart = midiEarly
                    midiDelayMs = (decidedDelaySec * 1000).toLong()

                    if (originalMidiNotes.isNotEmpty()) {
                        fixedMinPitch = (originalMidiNotes.minOf { it.note } - 4).coerceAtLeast(0)
                        fixedMaxPitch = (originalMidiNotes.maxOf { it.note } + 4).coerceAtMost(83)
                    } else {
                        fixedMinPitch = null
                        fixedMaxPitch = null
                    }

                    // MIDI를 “확정된 딜레이”로 즉시 반영
                    applyMidiTransposeAndDelayToView(resetLive = true)

                    fixedMinPitch?.let { fMin ->
                        fixedMaxPitch?.let { fMax ->
                            pitchView?.setPitchRange(fMin, fMax)
                        }
                    }

                    Log.e(
                        TAG,
                        "Delay decided at search stage. mp3OnsetIn4s=$mp3HasOnsetIn4s midiEarly=$midiEarly delaySec=$decidedDelaySec"
                    )

                    startButton.isEnabled = true
                    stopButton.isEnabled = true
                    stopSongButton.isEnabled = true
                }

            } catch (e: Exception) {
                Log.e(TAG, "searchAndLoad failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "오류: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) { searchButton.isEnabled = true }
            }
        }
    }

    /**
     * 조건(요청하신 최종 버전):
     * 1) mp3 확실한 음이 4초 안에 시작되지 않으면 -> 4초
     * 2) (1) 아니고, midi 첫 타일이 3초 안이면 -> 2.5초
     * 3) 그 외 -> 1초
     */
    private fun decideMidiDelaySeconds(mp3HasOnsetIn4s: Boolean, midiEarlyStart: Boolean): Double {
        return if (!mp3HasOnsetIn4s) {
            MIDI_DELAY_RULE1_SEC
        } else {
            if (midiEarlyStart) MIDI_DELAY_RULE2_SEC else MIDI_DELAY_RULE3_SEC
        }
    }

    // ===== Voice detector =====

    private fun ensureMicPermissionAndStartDetector() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) startVoiceDetectorIfNeeded()
        else requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startVoiceDetectorIfNeeded() {
        if (voiceDetector != null) return
        startPositionSampler()

        // 사용자가 겪었던 컴파일 에러(3-파라미터 기대) 기준으로 맞춤
        voiceDetector = VoicePitchDetector { hz: Float, _: Float, _: Float ->
            val timeMs = lastSafePlayerPositionMs
            val midi = if (hz > 0f) {
                (69.0 + 12.0 * log2(hz.toDouble() / 440.0)).toFloat()
            } else 0f

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                pitchView?.setDetectedPitch(midi)
                pianoRollView?.addLivePitchAt(timeMs, midi)
            }
        }

        voiceDetector?.start()
    }

    private fun stopVoiceDetector() {
        voiceDetector?.stop()
        voiceDetector = null
    }

    private fun startPositionSampler() {
        if (positionSamplerJob != null) return
        positionSamplerJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            while (true) {
                player?.let { lastSafePlayerPositionMs = it.currentPosition }
                delay(POSITION_SAMPLE_MS)
            }
        }
    }

    private fun stopPositionSampler() {
        positionSamplerJob?.cancel()
        positionSamplerJob = null
    }

    // ===== Player =====

    private fun preparePlayer(url: String) {
        releasePlayer()

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
                Log.e(TAG, "ExoPlayer error: ${error.errorCodeName}", error)
                Toast.makeText(requireContext(), "오디오 재생 오류: ${error.errorCodeName}", Toast.LENGTH_SHORT).show()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                lastSafePlayerPositionMs = exoPlayer.currentPosition
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                lastSafePlayerPositionMs = exoPlayer.currentPosition
            }
        })

        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        updatePlayerPitch()

        exoPlayer.prepare()
        exoPlayer.playWhenReady = false

        lastSafePlayerPositionMs = 0L
    }

    private fun updatePlayerPitch() {
        val p = player ?: return
        val factor = 2.0.pow(currentSemitones / 12.0).toFloat()
        p.playbackParameters = PlaybackParameters(1.0f, factor)
    }

    // ===== MIDI apply =====

    private fun applyMidiTransposeAndDelayToView(resetLive: Boolean) {
        val view = pianoRollView ?: return
        if (originalMidiNotes.isEmpty()) return

        val transposed = transposeNotes(originalMidiNotes, currentSemitones)
        val delayed = delayNotes(transposed, midiDelayMs)

        val fMin = fixedMinPitch
        val fMax = fixedMaxPitch
        if (fMin != null && fMax != null) {
            view.setNotes(delayed, fMin, fMax, resetLivePitches = resetLive)
        } else {
            view.setNotes(delayed)
        }
    }

    private fun transposeNotes(notes: List<MusicalNote>, semitones: Int): List<MusicalNote> {
        if (semitones == 0) return notes
        return notes.map { n ->
            val newNoteNumber = (n.note + semitones).coerceIn(0, 127)
            MusicalNote(newNoteNumber, n.startTime, n.duration)
        }
    }

    private fun delayNotes(notes: List<MusicalNote>, delayMs: Long): List<MusicalNote> {
        if (delayMs == 0L) return notes
        return notes.map { n ->
            MusicalNote(n.note, (n.startTime + delayMs).coerceAtLeast(0L), n.duration)
        }
    }

    // ===== Logger =====

    private fun startPositionLogger() {
        positionLogJob?.cancel()
        val p = player ?: return
        positionLogJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            val startTs = SystemClock.elapsedRealtime()
            while (true) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                Log.d(TAG, "posLog t=${(now - startTs) / 1000}s pos=${p.currentPosition} isPlaying=${p.isPlaying}")
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

    // ===== Network utils =====

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

    // ===== MIDI download/parse =====

    private fun downloadAndParseMidi(midiUrl: String): List<MusicalNote> {
        val bytes = httpGetBytes(midiUrl)
        val inputStream = ByteArrayInputStream(bytes)
        val parser = MidiParser()
        return parser.parse(inputStream)
    }

    // ===== MP3 onset detection (Mp3OnsetDetectorV3 사용) =====

    /**
     * MP3 파일을 다운로드 후,
     * Mp3OnsetDetectorV3로 0~windowSec 구간에서 "확실한(tonal) onset" 존재 여부를 판정.
     *
     * - "큰 잡음이어도 무음으로" 처리하기 위해 V3의 noiseLike 게이트를 사용.
     * - 분석 실패 시 UX 보호를 위해 보수적으로 true 반환(=Rule1(4초)을 강제로 걸지 않음).
     *   만약 실패도 무음으로 처리하고 싶으면 catch에서 false로 바꾸면 됩니다.
     */
    private fun detectMp3OnsetWithinWindowWithDetector(mp3Url: String, windowSec: Double): Boolean {
        val tmp = File.createTempFile("melody_", ".mp3", requireContext().cacheDir)
        try {
            val bytes = httpGetBytes(mp3Url)
            FileOutputStream(tmp).use { it.write(bytes) }

            // 호출/결과 로그를 강제로 남김(필터 상관없이 보이게)
            Log.e(TAG, "CALLING Mp3OnsetDetectorV3 windowSec=$windowSec tmp=${tmp.absolutePath}")

            Mp3OnsetDetectorV3.ANALYZE_SEC = windowSec
            Mp3OnsetDetectorV3.DEBUG = true

            val result = Mp3OnsetDetectorV3.analyze(requireContext(), android.net.Uri.fromFile(tmp))
            Log.e(TAG, "Mp3OnsetDetectorV3 result=$result")

            return result.hasOnset
        } catch (e: Exception) {
            Log.e(TAG, "detectMp3OnsetWithinWindowWithDetector failed", e)
            // 분석 실패 시 Rule1(4초)로 UX가 망가지는 걸 피하려면 true(기존 코드 정책)
            return true
            // 실패도 무음(=onset 없음)으로 처리하고 싶으면 return false 로 바꾸세요.
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun updateKeyText() {
        val label = when {
            currentSemitones == 0 -> "Key: 0 (원조)"
            currentSemitones > 0 -> "Key: +$currentSemitones"
            else -> "Key: $currentSemitones"
        }
        keyOffsetText.text = label
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
