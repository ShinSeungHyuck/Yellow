package com.example.yellow

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.yellow.data.FavoritesStore
import com.example.yellow.data.Song
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.MediaItem
import kotlinx.coroutines.*
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

class PianoFragment : Fragment(R.layout.fragment_piano) {

    companion object {
        private const val TAG = "PianoFragment"

        private const val MP3_ONSET_WINDOW_SEC = 4.0
        private const val MIDI_EARLY_START_SEC = 3.0

        // 자동 시간밀기(건드리지 않음)
        private const val MIDI_DELAY_RULE1_SEC = 4.15
        private const val MIDI_DELAY_RULE2_SEC = 2.7
        private const val MIDI_DELAY_RULE3_SEC = 0.8

        private const val POSITION_SAMPLE_MS = 50L
        private const val LYRICS_TICK_MS = 120L

        private const val LYRICS_VIEW_ID_NAME = "lyricsText"
    }

    // Topbar
    private lateinit var backBtn: ImageButton
    private lateinit var titleText: TextView
    private lateinit var favBtn: ImageButton
    private lateinit var loading: ProgressBar

    // Controls
    private lateinit var keySeekBar: SeekBar
    private lateinit var keyOffsetText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var stopSongButton: Button
    private lateinit var btnSeekMinus30: Button
    private lateinit var btnSeekMinus10: Button
    private lateinit var btnSeekPlus10: Button
    private lateinit var btnSeekPlus30: Button

    private var lyricsTextView: TextView? = null
    private var pianoRollView: PianoRollView? = null
    private var pitchView: PitchView? = null

    private var player: ExoPlayer? = null
    private var currentSemitones: Int = 0

    private var originalMidiNotes: List<MusicalNote> = emptyList()
    private var fixedMinPitch: Int? = null
    private var fixedMaxPitch: Int? = null
    private var midiDelayMs: Long = (MIDI_DELAY_RULE3_SEC * 1000).toLong()

    private var loadJob: Job? = null
    private var positionLogJob: Job? = null

    private var voiceDetector: VoicePitchDetector? = null
    @Volatile private var lastSafePlayerPositionMs: Long = 0L
    private var positionSamplerJob: Job? = null

    // Lyrics
    private data class LrcLine(val timeMs: Long, val text: String)
    private var lrcLines: List<LrcLine> = emptyList()
    private var lyricsJob: Job? = null

    // 현재 곡 (즐겨찾기/표시용)
    private var currentSong: Song? = null

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

        backBtn = view.findViewById(R.id.btn_back)
        titleText = view.findViewById(R.id.title_text)
        favBtn = view.findViewById(R.id.btn_favorite)
        loading = view.findViewById(R.id.loadingProgress)

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

        lyricsTextView = findOptionalTextViewByName(view, LYRICS_VIEW_ID_NAME)

        // 초기 비활성(로드 전)
        setControlsEnabled(false)

        backBtn.setOnClickListener { findNavController().navigateUp() }

        favBtn.setOnClickListener {
            currentSong?.let { s ->
                // ✅ 즐겨찾기 추가할 때 현재 keyOffset을 같이 저장
                val now = FavoritesStore.toggle(
                    requireContext(),
                    s,
                    keyOffsetWhenAdd = currentSemitones
                )
                updateFavoriteIcon(now)
            }
        }

        btnSeekMinus30.setOnClickListener { seekAndResetLive(-30_000L) }
        btnSeekMinus10.setOnClickListener { seekAndResetLive(-10_000L) }
        btnSeekPlus10.setOnClickListener  { seekAndResetLive(+10_000L) }
        btnSeekPlus30.setOnClickListener  { seekAndResetLive(+30_000L) }

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

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // ✅ 즐겨찾기 곡이면 keyOffset 저장(슬라이더 놓는 순간)
                currentSong?.let { s ->
                    if (FavoritesStore.isFavorite(requireContext(), s.id)) {
                        FavoritesStore.setKeyOffset(requireContext(), s.id, currentSemitones)
                    }
                }
            }
        })

        startButton.setOnClickListener {
            val p = player
            if (p == null) {
                Toast.makeText(requireContext(), "곡이 아직 로드되지 않았습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ensureMicPermissionAndStartDetector()
            p.playWhenReady = true
            p.play()
            startPositionSampler()
            startPositionLogger()
            startLyricsUpdater()
        }

        stopButton.setOnClickListener { player?.pause() }

        stopSongButton.setOnClickListener {
            val p = player ?: return@setOnClickListener
            p.pause()
            p.seekTo(0)
            lastSafePlayerPositionMs = 0L

            pianoRollView?.clearLivePitches()
            pitchView?.clearDetectedPitch()
            renderLyricsAtPlayerPos(0L)
        }

        // ---- args로 들어온 곡 로드 ----
        val title = requireArguments().getString("title").orEmpty()
        val melodyUrl = requireArguments().getString("melodyUrl").orEmpty()
        val midiUrl = requireArguments().getString("midiUrl").orEmpty()
        val queryTitle = requireArguments().getString("queryTitle").orEmpty()

        if (title.isBlank() || melodyUrl.isBlank() || midiUrl.isBlank()) {
            Toast.makeText(requireContext(), "곡 정보가 없습니다.", Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
            return
        }

        val id = Song.makeId(melodyUrl, midiUrl)
        currentSong = Song(id, title, melodyUrl, midiUrl, if (queryTitle.isBlank()) title else queryTitle)
        titleText.text = title

        // ✅ 즐겨찾기면 저장된 keyOffset 적용
        val isFav = FavoritesStore.isFavorite(requireContext(), id)
        updateFavoriteIcon(isFav)
        if (isFav) {
            val savedKey = FavoritesStore.getKeyOffset(requireContext(), id)
            currentSemitones = savedKey
            keySeekBar.progress = savedKey + 12
            updateKeyText()
        }

        loadSong(currentSong!!)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        startButton.isEnabled = enabled
        stopButton.isEnabled = enabled
        stopSongButton.isEnabled = enabled
        btnSeekMinus30.isEnabled = enabled
        btnSeekMinus10.isEnabled = enabled
        btnSeekPlus10.isEnabled = enabled
        btnSeekPlus30.isEnabled = enabled
        keySeekBar.isEnabled = enabled
    }

    private fun updateFavoriteIcon(isFav: Boolean) {
        favBtn.setImageResource(if (isFav) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
    }

    // ---- 기존 searchAndLoad를 "loadSong"으로 치환 (자동 시간밀기 로직 유지) ----
    private fun loadSong(song: Song) {
        loadJob?.cancel()
        loadJob = null

        lyricsJob?.cancel()
        lyricsJob = null
        lrcLines = emptyList()
        lyricsTextView?.text = ""

        setControlsEnabled(false)
        loading.visibility = View.VISIBLE

        originalMidiNotes = emptyList()
        fixedMinPitch = null
        fixedMaxPitch = null
        midiDelayMs = (MIDI_DELAY_RULE3_SEC * 1000).toLong()

        pianoRollView?.setNotes(emptyList())
        pianoRollView?.clearLivePitches()
        pitchView?.clearDetectedPitch()

        lastSafePlayerPositionMs = 0L

        loadJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val mp3OnsetDeferred = async(Dispatchers.IO) {
                    detectMp3OnsetWithinWindowWithDetector(song.melodyUrl, MP3_ONSET_WINDOW_SEC)
                }

                val midiNotes = downloadAndParseMidi(song.midiUrl)
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

                // 가사 로드: song.title(실제 곡명)을 우선 사용, queryTitle은 아티스트 힌트 추출용
                val lrcText = fetchSyncedLyricsFromLrclibBestTitleMatch(song.title, song.queryTitle)
                val parsedLrc = parseLrc(lrcText)

                withContext(Dispatchers.Main) {
                    preparePlayer(song.melodyUrl)

                    originalMidiNotes = midiNotes
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

                    // ✅ 즐겨찾기면 키 오프셋을 플레이어/뷰에 재적용
                    updatePlayerPitch()
                    applyMidiTransposeAndDelayToView(resetLive = true)

                    lrcLines = parsedLrc
                    renderLyricsAtPlayerPos(0L)

                    loading.visibility = View.GONE
                    setControlsEnabled(true)

                    Log.e(TAG, "Loaded. mp3OnsetIn4s=$mp3HasOnsetIn4s midiEarly=$midiEarly delaySec=$decidedDelaySec lrcLines=${lrcLines.size}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "loadSong failed", e)
                withContext(Dispatchers.Main) {
                    loading.visibility = View.GONE
                    Toast.makeText(requireContext(), "곡 로드 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    findNavController().navigateUp()
                }
            }
        }
    }

    private fun decideMidiDelaySeconds(mp3HasOnsetIn4s: Boolean, midiEarlyStart: Boolean): Double {
        return if (!mp3HasOnsetIn4s) {
            MIDI_DELAY_RULE1_SEC
        } else {
            if (midiEarlyStart) MIDI_DELAY_RULE2_SEC else MIDI_DELAY_RULE3_SEC
        }
    }

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

    // ---- Voice detector ----
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

    // ---- Player ----
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

    // ---- MIDI apply ----
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

    // ---- Logger ----
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

    // ---- Lyrics updater ----
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

    private fun renderLyricsAtPlayerPos(playerPosMs: Long) {
        val tv = lyricsTextView ?: return
        if (lrcLines.isEmpty()) {
            tv.text = ""
            return
        }

        // 자동 시간밀기(기존 동일): 가사도 midiDelayMs 만큼 지연 적용
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
            append(l1); append('\n')
            append(l2); append('\n')
            append(l3)
        }
    }

    // ---- Network utils ----
    private fun httpGetText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream.use { it.readBytes() }
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            return String(bytes)
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGetBytes(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
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

    private fun downloadAndParseMidi(midiUrl: String): List<MusicalNote> {
        val bytes = httpGetBytes(midiUrl)
        val inputStream = ByteArrayInputStream(bytes)
        val parser = MidiParser()
        return parser.parse(inputStream)
    }

    private fun detectMp3OnsetWithinWindowWithDetector(mp3Url: String, windowSec: Double): Boolean {
        val tmp = File.createTempFile("melody_", ".mp3", requireContext().cacheDir)
        try {
            val bytes = httpGetBytes(mp3Url)
            FileOutputStream(tmp).use { it.write(bytes) }

            Mp3OnsetDetectorV3.ANALYZE_SEC = windowSec
            Mp3OnsetDetectorV3.DEBUG = true

            val result = Mp3OnsetDetectorV3.analyze(requireContext(), Uri.fromFile(tmp))
            return result.hasOnset
        } catch (e: Exception) {
            Log.e(TAG, "detectMp3OnsetWithinWindowWithDetector failed", e)
            return true
        } finally {
            runCatching { tmp.delete() }
        }
    }

    // ---- LRCLIB ----
    /**
     * LRCLIB에서 동기화 가사를 검색한다.
     * - trackTitle: 실제 곡명 (API 검색 기준)
     * - queryTitle: 검색어 또는 카탈로그 원본 제목 → "곡명 - 아티스트" 형식이면 아티스트 추출에 활용
     */
    private fun fetchSyncedLyricsFromLrclibBestTitleMatch(trackTitle: String, queryTitle: String = ""): String {
        val title = trackTitle.trim()
        if (title.isBlank()) return ""

        // queryTitle이 "곡명 - 아티스트" 패턴이면 아티스트 힌트 추출 (카탈로그 곡에서 활용)
        val artistHint = run {
            val parts = queryTitle.split(" - ", limit = 2)
            if (parts.size == 2) parts[1].trim().ifBlank { null } else null
        }

        val titleEnc = URLEncoder.encode(title, "UTF-8")
        val searchUrl = if (!artistHint.isNullOrBlank()) {
            val artistEnc = URLEncoder.encode(artistHint, "UTF-8")
            "https://lrclib.net/api/search?track_name=$titleEnc&artist_name=$artistEnc"
        } else {
            "https://lrclib.net/api/search?track_name=$titleEnc"
        }

        return try {
            val body = httpGetText(searchUrl)
            val arr = JSONArray(body)
            if (arr.length() <= 0) return ""

            val bestObj = pickBestMatchByTitle(arr, title) ?: return ""
            val directSynced = bestObj.optString("syncedLyrics", "")
            if (directSynced.isNotBlank()) return directSynced

            val id = bestObj.optLong("id", -1L)
            if (id > 0) {
                val getUrl = "https://lrclib.net/api/get?id=$id"
                val getBody = httpGetText(getUrl)
                val obj = JSONObject(getBody)
                obj.optString("syncedLyrics", "")
            } else ""
        } catch (e: Exception) {
            Log.e(TAG, "LRCLIB fetch failed title=$title", e)
            ""
        }
    }

    private fun pickBestMatchByTitle(arr: JSONArray, inputTitle: String): JSONObject? {
        val inNorm = normalizeTitle(inputTitle)
        var bestObj: JSONObject? = null
        var bestScore = -1.0

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val track = o.optString("trackName", o.optString("track_name", ""))
            if (track.isBlank()) continue

            val score = improvedTitleSimilarity(inNorm, normalizeTitle(track))
            if (score > bestScore) {
                bestScore = score
                bestObj = o
            }
        }

        // 최고 점수가 임계값 미만이면 매칭 실패로 처리 (엉뚱한 가사 표시 방지)
        val MIN_MATCH_SCORE = 0.4
        return if (bestScore >= MIN_MATCH_SCORE) bestObj else null
    }

    private fun normalizeTitle(s: String): String {
        return s.lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[^0-9a-zA-Z가-힣]"), "")
    }

    /**
     * 가사 제목 유사도 계산 (개선된 버전)
     * 단순 Levenshtein 대신 포함 관계를 우선 체크하여 정확도 향상
     */
    private fun improvedTitleSimilarity(qNorm: String, titleNorm: String): Double {
        if (qNorm.isEmpty() && titleNorm.isEmpty()) return 1.0
        if (qNorm.isEmpty() || titleNorm.isEmpty()) return 0.0

        // 1. 완전 일치
        if (qNorm == titleNorm) return 1.0

        // 2. 검색어가 제목 안에 포함 (예: "어제의나에게" 검색 → lrclib의 "어제의 나에게" 매칭)
        val idx = titleNorm.indexOf(qNorm)
        if (idx >= 0) {
            val coverage = qNorm.length.toDouble() / titleNorm.length
            val posBonus = if (idx == 0) 0.1 else 0.0
            return (0.6 + posBonus + 0.3 * coverage).coerceIn(0.0, 1.0)
        }

        // 3. 제목이 검색어 안에 포함 (lrclib 제목이 더 짧은 경우)
        val idxRev = qNorm.indexOf(titleNorm)
        if (idxRev >= 0) {
            val coverage = titleNorm.length.toDouble() / qNorm.length
            val posBonus = if (idxRev == 0) 0.1 else 0.0
            return (0.5 + posBonus + 0.3 * coverage).coerceIn(0.0, 1.0)
        }

        // 4. 포함 관계 없으면 Levenshtein 폴백 (오타 허용)
        val dist = levenshtein(qNorm, titleNorm)
        val maxLen = maxOf(qNorm.length, titleNorm.length).toDouble()
        return 1.0 - (dist / maxLen)
    }

    private fun titleSimilarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dist = levenshtein(a, b)
        val maxLen = maxOf(a.length, b.length).toDouble()
        return 1.0 - (dist / maxLen)
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun parseLrc(lrc: String): List<LrcLine> {
        if (lrc.isBlank()) return emptyList()
        val lines = ArrayList<LrcLine>()
        val regex = Regex("^\\[(\\d+):(\\d+)(?:\\.(\\d+))?] ?(.*)$")
        lrc.lineSequence().forEach { raw ->
            val m = regex.find(raw) ?: return@forEach
            val mm = m.groupValues[1].toLongOrNull() ?: return@forEach
            val ss = m.groupValues[2].toLongOrNull() ?: return@forEach
            val frac = m.groupValues[3]
            val text = m.groupValues[4].trim()

            val ms = when (frac.length) {
                0 -> 0L
                1 -> (frac.toLongOrNull() ?: 0L) * 100L
                2 -> (frac.toLongOrNull() ?: 0L) * 10L
                else -> (frac.take(3).toLongOrNull() ?: 0L)
            }
            val timeMs = (mm * 60_000L) + (ss * 1000L) + ms
            lines.add(LrcLine(timeMs, text))
        }
        return lines.sortedBy { it.timeMs }
    }

    private fun findOptionalTextViewByName(root: View, idName: String): TextView? {
        val ctx = root.context
        val id = ctx.resources.getIdentifier(idName, "id", ctx.packageName)
        if (id == 0) return null
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

        loadJob?.cancel()
        loadJob = null

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