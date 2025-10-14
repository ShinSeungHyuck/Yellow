package com.example.yellow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.View
import kotlin.math.log2

class PianoRollView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var notes: List<MusicalNote> = emptyList()
    private val notePaint = Paint()
    private val gridPaint = Paint()
    private val textPaint = Paint()
    private val livePitchPaint = Paint()

    private val noteColors = listOf(
        Color.parseColor("#FF5733"), Color.parseColor("#33FF57"), Color.parseColor("#3357FF"),
        Color.parseColor("#FFFF33"), Color.parseColor("#FF33FF"), Color.parseColor("#33FFFF"),
        Color.parseColor("#FFC300"), Color.parseColor("#DAF7A6"), Color.parseColor("#C70039"),
        Color.parseColor("#900C3F"), Color.parseColor("#581845"), Color.parseColor("#A569BD")
    )

    var minPitch = 48
        private set
    var maxPitch = 72
        private set
    private var totalDurationMs = 10000L

    private val timeAxisHeight = 60f
    private var keyHeight = 40f // This is now calculated dynamically
    private val pixelsPerSecond = 100f // Increased pixels per second for better visualization

    private val livePitches = mutableListOf<Pair<Long, Float>>()
    private var recordingStartTime = -1L
    private val clipBounds = Rect()

    init {
        gridPaint.color = Color.LTGRAY
        gridPaint.strokeWidth = 1f
        textPaint.color = Color.DKGRAY
        textPaint.textSize = 28f
        textPaint.isAntiAlias = true
        livePitchPaint.color = Color.BLUE
        livePitchPaint.strokeWidth = 5f
        notePaint.style = Paint.Style.FILL
    }

    fun setNotes(newNotes: List<MusicalNote>) {
        this.notes = newNotes.sortedBy { it.startTime }
        if (newNotes.isNotEmpty()) {
            minPitch = (newNotes.minOf { it.note } - 4).coerceAtLeast(0)
            maxPitch = (newNotes.maxOf { it.note } + 4).coerceAtMost(83) // Cap max pitch to B5 (83)
            totalDurationMs = newNotes.maxOfOrNull { it.startTime + it.duration } ?: 10000L
            Log.d("PianoRollView", "Notes set. Count: ${notes.size}, Duration: ${totalDurationMs}ms, Pitch Range: $minPitch-$maxPitch")
        }
        livePitches.clear()
        recordingStartTime = -1L
        updateKeyHeight() // Recalculate key height as the range might have changed
        requestLayout()
        invalidate()
    }

    val currentMinPitch: Int get() = minPitch
    val currentMaxPitch: Int get() = maxPitch

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h > 0) {
            updateKeyHeight() // Update key height when view size is known
        }
    }

    private fun updateKeyHeight() {
        if (height == 0) return // Don't do anything if height is not set yet
        val availableHeight = height - timeAxisHeight
        val pitchRange = (maxPitch - minPitch + 1).coerceAtLeast(1)
        keyHeight = availableHeight / pitchRange
        invalidate() // Redraw as key height has changed
    }


    fun addLivePitch(pitchInHz: Float) {
        if (recordingStartTime == -1L) {
            recordingStartTime = System.currentTimeMillis()
        }
        val elapsedTime = System.currentTimeMillis() - recordingStartTime
        val midiNote = if (pitchInHz > 0) (69 + 12 * log2(pitchInHz / 440f)) else 0.0
        livePitches.add(Pair(elapsedTime, midiNote.toFloat()))
        invalidate()
    }

    fun clearLivePitches() {
        livePitches.clear()
        recordingStartTime = -1L
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
        val pitchRange = maxPitch - minPitch

        // --- Vertical Grid Lines (Time) ---
        val firstSecond = (clipBounds.left / pixelsPerSecond).toInt().coerceAtLeast(0)
        val lastSecond = ((clipBounds.right / pixelsPerSecond) + 1).toInt().coerceAtMost((totalDurationMs/1000).toInt())

        textPaint.textAlign = Paint.Align.CENTER
        for (sec in firstSecond..lastSecond) {
            val x = sec * pixelsPerSecond
            if (sec % 5 == 0) { // Display label every 5 seconds
                canvas.drawLine(x, 0f, x, viewHeight, gridPaint)
                canvas.drawText("${sec}s", x, viewHeight + 40f, textPaint)
            } else { // Just draw a smaller line for other seconds
                 canvas.drawLine(x, 0f, x, viewHeight, gridPaint)
            }
        }

        // --- Horizontal Grid Lines (Pitch) ---
        for (i in 0..pitchRange) {
            val y = viewHeight - (i * keyHeight)
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
        }
    }

    private fun drawNotes(canvas: Canvas) {
        if (notes.isEmpty()) return

        val viewHeight = height - timeAxisHeight
        val pixelsPerMs = pixelsPerSecond / 1000f

        for (note in notes) {
            val noteLeft = (note.startTime * pixelsPerMs)
            val noteRight = noteLeft + (note.duration * pixelsPerMs)

            if (noteRight < clipBounds.left || noteLeft > clipBounds.right) {
                continue
            }

            val noteTop = viewHeight - ((note.note - minPitch + 1) * keyHeight)
            val noteBottom = noteTop + keyHeight

            notePaint.color = noteColors[note.note % noteColors.size]
            canvas.drawRect(noteLeft, noteTop, noteRight, noteBottom, notePaint)
        }
    }

    private fun drawLivePitches(canvas: Canvas) {
        if (livePitches.size < 2) return
        val viewHeight = height - timeAxisHeight
        val pixelsPerMs = pixelsPerSecond / 1000f

        for (i in 0 until livePitches.size - 1) {
            val p1 = livePitches[i]
            val p2 = livePitches[i + 1]

            val x1 = p1.first * pixelsPerMs
            val y1 = viewHeight - ((p1.second - minPitch) * keyHeight)

            val x2 = p2.first * pixelsPerMs
            val y2 = viewHeight - ((p2.second - minPitch) * keyHeight)

            if (x2 < clipBounds.left || x1 > clipBounds.right) {
                continue
            }

            canvas.drawLine(x1, y1, x2, y2, livePitchPaint)
        }
    }
}
//좋아
