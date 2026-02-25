package com.example.yellow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.View
import kotlin.math.log2
import kotlin.math.roundToInt

class PianoRollView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var notes: List<MusicalNote> = emptyList()
    private val notePaint = Paint()
    private val gridPaint = Paint()
    private val textPaint = Paint()
    private val livePitchPaint = Paint()

    private val noteGrayColor = Color.parseColor("#9E9E9E")

    // 음표 이름 배열 (MIDI 음번호 → 이름 변환)
    private val noteNames = arrayOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")
    private fun midiToNoteName(midi: Int): String {
        val octave = (midi / 12) - 1
        return noteNames[midi % 12] + octave
    }

    var minPitch = 48
        private set
    var maxPitch = 72
        private set

    private var totalDurationMs = 10_000L

    private val timeAxisHeight = 60f
    private var keyHeight = 40f
    private val pixelsPerSecond = 100f

    // livePitches: (timelineMs, midiNoteFloat)
    // timelineMs는 "0부터 시작하는 상대시간(ms)" 이어야 정상 동작함.
    private val livePitches = mutableListOf<Pair<Long, Float>>()
    private val clipBounds = Rect()

    // (중요) addLivePitch(Hz)에서 사용할 "상대시간 기준점"
    private var liveBaseElapsedRealtime: Long? = null

    init {
        gridPaint.color = Color.LTGRAY
        gridPaint.strokeWidth = 1f

        textPaint.color = Color.parseColor("#B0ACC8")
        textPaint.textSize = 28f
        textPaint.isAntiAlias = true

        livePitchPaint.color = Color.parseColor("#00E5FF")
        livePitchPaint.style = Paint.Style.FILL

        notePaint.style = Paint.Style.FILL
    }

    // 외부에서 기준점 설정(예: 녹음 시작 시점 / 플레이 시작 시점)
    fun resetLiveTimelineBase(baseElapsedRealtime: Long = SystemClock.elapsedRealtime()) {
        liveBaseElapsedRealtime = baseElapsedRealtime
    }

    // 기존 유지
    fun setNotes(newNotes: List<MusicalNote>) {
        setNotes(newNotes, null, null, resetLivePitches = true)
    }

    // 고정 range 지원 + 라이브피치 유지 옵션
    fun setNotes(
        newNotes: List<MusicalNote>,
        fixedMinPitch: Int?,
        fixedMaxPitch: Int?,
        resetLivePitches: Boolean
    ) {
        this.notes = newNotes.sortedBy { it.startTime }

        if (newNotes.isNotEmpty()) {
            val computedMin = (newNotes.minOf { it.note } - 4).coerceAtLeast(0)
            val computedMax = (newNotes.maxOf { it.note } + 4).coerceAtMost(83)

            minPitch = fixedMinPitch ?: computedMin
            maxPitch = fixedMaxPitch ?: computedMax

            totalDurationMs = newNotes.maxOfOrNull { it.startTime + it.duration } ?: 10_000L
            Log.d("PianoRollView", "Notes set. Count=${notes.size}, Duration=${totalDurationMs}ms, Range=$minPitch-$maxPitch")
        } else {
            totalDurationMs = 10_000L
        }

        if (resetLivePitches) {
            livePitches.clear()
        }

        updateKeyHeight()
        requestLayout()
        invalidate()
    }

    val currentMinPitch: Int get() = minPitch
    val currentMaxPitch: Int get() = maxPitch

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateKeyHeight()
    }

    private fun updateKeyHeight() {
        if (height == 0) return
        val availableHeight = height - timeAxisHeight
        val pitchRange = (maxPitch - minPitch + 1).coerceAtLeast(1)
        keyHeight = availableHeight / pitchRange
        invalidate()
    }

    /**
     * (A) Hz 입력: "상대시간(ms)" 기반으로 타임라인에 추가
     * - 기존 코드의 System.currentTimeMillis() 사용이 문제를 만들었음
     * - elapsedRealtime() 기준점(liveBaseElapsedRealtime)으로 상대시간을 계산
     */
    fun addLivePitch(pitchInHz: Float) {
        if (pitchInHz <= 0) return

        val midiNote = (69 + 12 * log2(pitchInHz / 440f)).toFloat()

        val base = liveBaseElapsedRealtime ?: run {
            val now = SystemClock.elapsedRealtime()
            liveBaseElapsedRealtime = now
            now
        }

        val now = SystemClock.elapsedRealtime()
        val timelineMs = (now - base).coerceAtLeast(0L)

        addLivePitchAt(timelineMs, midiNote)
    }

    /**
     * (B) 외부 타임라인 직접 주입 (권장)
     * - 예: player.currentPosition(ms) 를 그대로 넣어야 음악과 정렬됨
     */
    fun addLivePitchAt(timeMs: Long, midiNote: Float) {
        livePitches.add(timeMs to midiNote)

        // view 폭이 너무 짧아서 잘리는 것을 방지
        if (timeMs + 2000 > totalDurationMs) {
            totalDurationMs = timeMs + 2000
            requestLayout()
        }

        invalidate()
    }

    fun clearLivePitches() {
        livePitches.clear()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (totalDurationMs / 1000f * pixelsPerSecond).toInt()
        val measuredHeight = getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(desiredWidth, measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.getClipBounds(clipBounds)
        drawGridAndLabels(canvas)
        drawNotes(canvas)
        drawLivePitches(canvas)
    }

    private fun drawGridAndLabels(canvas: Canvas) {
        val viewHeight = height - timeAxisHeight

        // 시간 레이블만 표시 (격자선 없음)
        val firstSecond = (clipBounds.left / pixelsPerSecond).toInt().coerceAtLeast(0)
        val lastSecond = ((clipBounds.right / pixelsPerSecond) + 1).toInt()
            .coerceAtMost((totalDurationMs / 1000).toInt() + 1)
        textPaint.textAlign = Paint.Align.CENTER
        for (sec in firstSecond..lastSecond) {
            if (sec % 5 == 0) {
                val x = sec * pixelsPerSecond
                canvas.drawText("${sec}s", x, viewHeight + 40f, textPaint)
            }
        }

        // 음표 레이블은 PitchView(스크롤 바깥)에서 고정 표시
    }

    private fun drawNotes(canvas: Canvas) {
        if (notes.isEmpty()) return

        val viewHeight = height - timeAxisHeight
        val pixelsPerMs = pixelsPerSecond / 1000f

        for (note in notes) {
            val left = note.startTime * pixelsPerMs
            val right = left + note.duration * pixelsPerMs

            if (right < clipBounds.left || left > clipBounds.right) continue

            val top = viewHeight - ((note.note - minPitch + 1) * keyHeight)
            val bottom = top + keyHeight

            notePaint.color = noteGrayColor
            canvas.drawRect(left, top, right, bottom, notePaint)
        }
    }

    private fun drawLivePitches(canvas: Canvas) {
        if (livePitches.size < 2) return

        val viewHeight = height - timeAxisHeight
        val pixelsPerMs = pixelsPerSecond / 1000f

        for (i in 0 until livePitches.size - 1) {
            val p1 = livePitches[i]
            val p2 = livePitches[i + 1]

            // 타임라인이 역전되면 스킵(방어)
            if (p2.first <= p1.first) continue

            val roundedMidi = p1.second.roundToInt()
            if (roundedMidi < minPitch || roundedMidi > maxPitch) continue

            val left = p1.first * pixelsPerMs
            val right = p2.first * pixelsPerMs

            if (right < clipBounds.left || left > clipBounds.right) continue

            val top = viewHeight - ((roundedMidi - minPitch + 1) * keyHeight)
            val bottom = top + keyHeight

            canvas.drawRect(left, top, right, bottom, livePitchPaint)
        }
    }
}
