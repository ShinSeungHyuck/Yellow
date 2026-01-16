package com.example.yellow

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.max
import kotlin.math.min

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

        // 가사 업데이트 주기
        private const val LYRICS_TICK_MS = 120L

        // 레이아웃에 가사 TextView를 넣을 때 사용할 id 이름(문자열)
        // XML에 @+id/lyricsText 로 추가하면 자동 연결됨
        private const val LYRICS_VIEW_ID_NAME = "lyricsText"
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

    private lateinit var btnSeekMinus30: Button
    private lateinit var btnSeekMinus10: Button
    private lateinit var btnSeekPlus10: Button
    private lateinit var btnSeekPlus30: Button

    // 가사 표시 (레이아웃에 없으면 null로 유지 — 컴파일/런타임 안전)
    private var lyricsTextView: TextView? = null

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

    // ===== Lyrics (LRCLIB) =====
    private data class LrcLine(val timeMs: Long, val text: String)

    private var lrcLines: List<LrcLine> = emptyList()
    private var lyricsJob: Job? = null

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

        btnSeekMinus30 = view.findViewById(R.id.btn_seek_minus_30)
        btnSeekMinus10 = view.findViewById(R.id.btn_seek_minus_10)
        btnSeekPlus10 = view.findViewById(R.id.btn_seek_plus_10)
        btnSeekPlus30 = view.findViewById(R.id.btn_seek_plus_30)

        // ===== 핵심: lyricsText를 R.id로 직접 참조하지 않고, 있으면 연결 =====
        lyricsTextView = findOptionalTextViewByName(view, LYRICS_VIEW_ID_NAME)

        // 기본은 비활성(곡 로드 전)
        btnSeekMinus30.isEnabled = false
        btnSeekMinus10.isEnabled = false
        btnSeekPlus10.isEnabled = false
        btnSeekPlus30.isEnabled = false

        btnSeekMinus30.setOnClickListener { seekAndResetLive(-30_000L) }
        btnSeekMinus10.setOnClickListener { seekAndResetLive(-10_000L) }
        btnSeekPlus10.setOnClickListener  { seekAndResetLive(+10_000L) }
        btnSeekPlus30.setOnClickListener  { seekAndResetLive(+30_000L) }

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
                Toast.makeText(requireContext(), "검색어(제목)를 입력하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 이제 입력은 "제목"만. 예) "밤편지"
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

            startPositionSampler()
            startPositionLogger()
            startLyricsUpdater()
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

            renderLyricsAtPlayerPos(0L)
        }
    }

    // ===== 검색/확정 단계에서 MIDI 딜레이 + 가사 로드까지 확정 =====
    private fun searchAndLoad(queryTitleOnly: String) {
        searchJob?.cancel()
        searchJob = null

        lyricsJob?.cancel()
        lyricsJob = null
        lrcLines = emptyList()
        lyricsTextView?.text = ""

        searchButton.isEnabled = false
        startButton.isEnabled = false
        stopButton.isEnabled = false
        stopSongButton.isEnabled = false

        btnSeekMinus30.isEnabled = false
        btnSeekMinus10.isEnabled = false
        btnSeekPlus10.isEnabled = false
        btnSeekPlus30.isEnabled = false

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
                val encoded = URLEncoder.encode(queryTitleOnly, "UTF-8")

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

                val mp3OnsetDeferred = async(Dispatchers.IO) {
                    detectMp3OnsetWithinWindowWithDetector(melodyUrl, MP3_ONSET_WINDOW_SEC)
                }

                val midiNotes = downloadAndParseMidi(midiUrl)
                val mp3HasOnsetIn4s = mp3OnsetDeferred.await()

                val midiEarly = if (midiNotes.isNotEmpty()) {
                    val firstStartMs = midiNotes.minOf { it.startTime }
                    firstStartMs <= (MIDI_EARLY_START_SEC * 1000).toLong()
                } else false

                val decidedDelaySec = decideMidiDelaySeconds(
                    mp3HasOnsetIn4s = mp3HasOnsetIn4s,
                    midiEarlyStart = midiEarly
                )
                val decidedDelayMs = (decidedDelaySec * 1000).toLong()

                // ===== LRCLIB 가사 로드: 제목만 검색 + 유사도 최고 항목 선택 =====
                val lrcText = fetchSyncedLyricsFromLrclibBestTitleMatch(queryTitleOnly)
                val parsedLrc = parseLrc(lrcText)

                withContext(Dispatchers.Main) {
                    currentMelodyUrl = melodyUrl
                    currentSongTitle = melodyKey ?: queryTitleOnly
                    currentSongText.text = "현재 곡: ${currentSongTitle ?: "없음"}"

                    preparePlayer(melodyUrl)

                    originalMidiNotes = midiNotes
                    isMidiEarlyStart = midiEarly
                    midiDelayMs = decidedDelayMs

                    if (originalMidiNotes.isNotEmpty()) {
                        fixedMinPitch = (originalMidiNotes.minOf { it.note } - 4).coerceAtLeast(0)
                        fixedMaxPitch = (originalMidiNotes.maxOf { it.note } + 4).coerceAtMost(83)
                    } else {
                        fixedMinPitch = null
                        fixedMaxPitch = null
                    }

                    applyMidiTransposeAndDelayToView(resetLive = true)

                    fixedMinPitch?.let { fMin ->
                        fixedMaxPitch?.let { fMax ->
                            pitchView?.setPitchRange(fMin, fMax)
                        }
                    }

                    // 가사 저장 + 전주(0초)부터 3줄 표시
                    lrcLines = parsedLrc
                    renderLyricsAtPlayerPos(0L)

                    Log.e(
                        TAG,
                        "Delay decided. mp3OnsetIn4s=$mp3HasOnsetIn4s midiEarly=$midiEarly delaySec=$decidedDelaySec lrcLines=${lrcLines.size}"
                    )

                    startButton.isEnabled = true
                    stopButton.isEnabled = true
                    stopSongButton.isEnabled = true

                    btnSeekMinus30.isEnabled = true
                    btnSeekMinus10.isEnabled = true
                    btnSeekPlus10.isEnabled = true
                    btnSeekPlus30.isEnabled = true
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
     * 조건:
     * 1) mp3 확실한 음이 4초 안에 시작되지 않으면 -> RULE1
     * 2) (1) 아니고, midi 첫 타일이 3초 안이면 -> RULE2
     * 3) 그 외 -> RULE3
     */
    private fun decideMidiDelaySeconds(mp3HasOnsetIn4s: Boolean, midiEarlyStart: Boolean): Double {
        return if (!mp3HasOnsetIn4s) {
            MIDI_DELAY_RULE1_SEC
        } else {
            if (midiEarlyStart) MIDI_DELAY_RULE2_SEC else MIDI_DELAY_RULE3_SEC
        }
    }

    // ===== Seek buttons: 시점 이동 + 라이브 피치 초기화 =====
    private fun seekAndResetLive(deltaMs: Long) {
        val p = player ?: return

        val duration = p.duration
        val cur = p.currentPosition
        var newPos = cur + deltaMs
        if (newPos < 0) newPos = 0
        if (duration > 0 && newPos > duration) newPos = duration

        p.seekTo(newPos)
        lastSafePlayerPositionMs = newPos

        pianoRollView?.clearLivePitches()
        pitchView?.clearDetectedPitch()

        renderLyricsAtPlayerPos(newPos)
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

        lyricsJob?.cancel()
        lyricsJob = null

        player?.release()
        player = null
        lastSafePlayerPositionMs = 0L
    }

    // ===== Lyrics updater =====
    private fun startLyricsUpdater() {
        if (lyricsJob != null) return
        val tv = lyricsTextView ?: return
        if (lrcLines.isEmpty()) {
            tv.text = ""
            return
        }

        lyricsJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            while (true) {
                val pos = player?.currentPosition ?: lastSafePlayerPositionMs
                renderLyricsAtPlayerPos(pos)
                delay(LYRICS_TICK_MS)
            }
        }
    }

    /**
     * - 가사 전환 시간도 midiDelayMs 만큼 "미뤄짐"
     * - 전주(0초)부터 가사 3줄 표시
     * - 전환은 다음 가사 timestamp에 맞춰(조기 전환 X)
     */
    private fun renderLyricsAtPlayerPos(playerPosMs: Long) {
        val tv = lyricsTextView ?: return
        if (lrcLines.isEmpty()) {
            tv.text = ""
            return
        }

        // 가사 타임라인을 MIDI/마이크 비교와 동일한 룰 딜레이로 지연
        val base = playerPosMs - midiDelayMs

        val idx = when {
            base < lrcLines[0].timeMs -> 0
            else -> {
                var lo = 0
                var hi = lrcLines.lastIndex
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    if (lrcLines[mid].timeMs <= base) lo = mid + 1 else hi = mid - 1
                }
                hi.coerceIn(0, lrcLines.lastIndex)
            }
        }

        val l1 = lrcLines.getOrNull(idx)?.text.orEmpty()
        val l2 = lrcLines.getOrNull(idx + 1)?.text.orEmpty()
        val l3 = lrcLines.getOrNull(idx + 2)?.text.orEmpty()

        tv.text = buildString {
            append(l1)
            append('\n')
            append(l2)
            append('\n')
            append(l3)
        }
    }

    // ===== Network utils =====
    private fun httpGetText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/json")

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
    private fun detectMp3OnsetWithinWindowWithDetector(mp3Url: String, windowSec: Double): Boolean {
        val tmp = File.createTempFile("melody_", ".mp3", requireContext().cacheDir)
        try {
            val bytes = httpGetBytes(mp3Url)
            FileOutputStream(tmp).use { it.write(bytes) }

            Log.e(TAG, "CALLING Mp3OnsetDetectorV3 windowSec=$windowSec tmp=${tmp.absolutePath}")

            Mp3OnsetDetectorV3.ANALYZE_SEC = windowSec
            Mp3OnsetDetectorV3.DEBUG = true

            val result = Mp3OnsetDetectorV3.analyze(requireContext(), Uri.fromFile(tmp))
            Log.e(TAG, "Mp3OnsetDetectorV3 result=$result")

            return result.hasOnset
        } catch (e: Exception) {
            Log.e(TAG, "detectMp3OnsetWithinWindowWithDetector failed", e)
            return true
        } finally {
            runCatching { tmp.delete() }
        }
    }

    // ===== LRCLIB: 제목만 검색 + 유사도 최고 선택 =====

    /**
     * LRCLIB에서 track_name(제목)만으로 검색하고,
     * 결과 중 "입력 제목과 가장 유사한 track title"을 선택해서 syncedLyrics를 가져옵니다.
     */
    private fun fetchSyncedLyricsFromLrclibBestTitleMatch(inputTitle: String): String {
        val title = inputTitle.trim()
        if (title.isBlank()) return ""

        val titleEnc = URLEncoder.encode(title, "UTF-8")
        val searchUrl = "https://lrclib.net/api/search?track_name=$titleEnc"

        return try {
            val body = httpGetText(searchUrl)
            val arr = JSONArray(body)
            if (arr.length() <= 0) return ""

            val bestObj = pickBestMatchByTitle(arr, title)
            if (bestObj == null) return ""

            val directSynced = bestObj.optString("syncedLyrics", "")
            if (directSynced.isNotBlank()) return directSynced

            val id = bestObj.optLong("id", -1L)
            if (id > 0) {
                val getUrl = "https://lrclib.net/api/get?id=$id"
                val getBody = httpGetText(getUrl)
                val obj = JSONObject(getBody)
                obj.optString("syncedLyrics", "")
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "LRCLIB fetch failed title=$title", e)
            ""
        }
    }

    /**
     * JSONArray 결과에서 track title을 뽑아 "정규화 + Levenshtein 유사도"가 가장 높은 항목을 고릅니다.
     * - 동점이면 더 짧은 거리(=더 가까움) 우선
     */
    private fun pickBestMatchByTitle(arr: JSONArray, inputTitle: String): JSONObject? {
        val normInput = normalizeTitle(inputTitle)
        var best: JSONObject? = null
        var bestScore = -1.0
        var bestDist = Int.MAX_VALUE

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val candTitle = optStringAny(obj, "trackName", "track_name", "track", "title", "name")
            if (candTitle.isBlank()) continue

            val normCand = normalizeTitle(candTitle)
            val (score, dist) = titleSimilarity(normInput, normCand)

            if (score > bestScore || (score == bestScore && dist < bestDist)) {
                bestScore = score
                bestDist = dist
                best = obj
            }
        }

        best?.let {
            val chosenTitle = optStringAny(it, "trackName", "track_name", "track", "title", "name")
            val chosenArtist = optStringAny(it, "artistName", "artist_name", "artist")
            Log.d(TAG, "LRCLIB best match title='$chosenTitle' artist='$chosenArtist' score=$bestScore dist=$bestDist")
        }
        return best
    }

    /**
     * 문자열 정규화:
     * - 공백/구두점/특수문자 제거
     * - 소문자화
     */
    private fun normalizeTitle(s: String): String {
        val lower = s.lowercase()
        // 한글/영문/숫자만 남김 (공백/특수문자 제거)
        return lower.replace(Regex("[^0-9a-z가-힣]+"), "")
    }

    /**
     * 유사도(0~1) + 거리 반환
     * - score = 1 - dist/maxLen
     */
    private fun titleSimilarity(normA: String, normB: String): Pair<Double, Int> {
        if (normA.isEmpty() && normB.isEmpty()) return 1.0 to 0
        if (normA.isEmpty() || normB.isEmpty()) return 0.0 to max(normA.length, normB.length)

        val dist = levenshteinDistance(normA, normB)
        val maxLen = max(normA.length, normB.length).coerceAtLeast(1)
        val score = 1.0 - (dist.toDouble() / maxLen.toDouble())
        return score to dist
    }

    /**
     * Levenshtein 거리 (O(n*m), 짧은 제목 문자열엔 충분히 빠름)
     */
    private fun levenshteinDistance(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        if (n == 0) return m
        if (m == 0) return n

        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)

        for (i in 1..n) {
            cur[0] = i
            val ca = a[i - 1]
            for (j in 1..m) {
                val cb = b[j - 1]
                val cost = if (ca == cb) 0 else 1
                cur[j] = min(
                    min(cur[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[m]
    }

    private fun optStringAny(obj: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = obj.optString(k, "")
            if (v.isNotBlank()) return v
        }
        return ""
    }

    /**
     * LRC 파서:
     * - [mm:ss.xx] 또는 [mm:ss] 형태 지원
     * - 여러 타임태그가 한 줄에 있는 경우 처리
     */
    private fun parseLrc(lrc: String): List<LrcLine> {
        if (lrc.isBlank()) return emptyList()

        val lines = mutableListOf<LrcLine>()
        val regex = Regex("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?]")

        lrc.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach

            val matches = regex.findAll(line).toList()
            if (matches.isEmpty()) return@forEach

            val text = line.replace(regex, "").trim()
            if (text.isEmpty()) return@forEach

            for (m in matches) {
                val mm = m.groupValues[1].toLongOrNull() ?: continue
                val ss = m.groupValues[2].toLongOrNull() ?: continue
                val fracStr = m.groupValues[3]
                val ms = when {
                    fracStr.isBlank() -> 0L
                    fracStr.length == 1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                    fracStr.length == 2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                    else -> (fracStr.take(3).toLongOrNull() ?: 0L)
                }

                val timeMs = (mm * 60_000L) + (ss * 1000L) + ms
                lines.add(LrcLine(timeMs, text))
            }
        }

        return lines.sortedBy { it.timeMs }
    }

    // ===== Optional View Finder (중요: R.id로 직접 참조하지 않음) =====
    private fun findOptionalTextViewByName(root: View, idName: String): TextView? {
        val ctx = root.context
        val id = ctx.resources.getIdentifier(idName, "id", ctx.packageName)
        if (id == 0) {
            Log.w(TAG, "Optional TextView id not found in resources: $idName (skip lyrics UI)")
            return null
        }
        return root.findViewById(id)
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

        lyricsJob?.cancel()
        lyricsJob = null

        stopVoiceDetector()
        stopPositionSampler()
        releasePlayer()

        pianoRollView = null
        pitchView = null
        lyricsTextView = null
    }
}
