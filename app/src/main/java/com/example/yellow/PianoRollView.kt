package com.example.yellow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.View
import kotlin.math.log2
import kotlin.math.max

class PianoRollView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    // ... (existing properties) ...
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

    private val pitchNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    private var minPitch = 48 // Default C3
    private var maxPitch = 72 // Default C5
    private var totalDurationMs = 10000L // Default 10 seconds

    private val pitchLabelWidth = 100f
    private val timeAxisHeight = 60f
    private val keyHeight = 40f
    private val pixelsPerSecond = 150f

    // Data structure to hold live pitch points
    private val livePitches = mutableListOf<Pair<Long, Float>>()
    private var recordingStartTime = -1L

    init {
        // ... (existing init block) ...
        gridPaint.color = Color.parseColor("#e0e0e0")
        gridPaint.strokeWidth = 1f
        textPaint.color = Color.DKGRAY
        textPaint.textSize = 28f
        textPaint.isAntiAlias = true
        livePitchPaint.color = Color.BLUE
        livePitchPaint.strokeWidth = 5f
    }

    fun setNotes(newNotes: List<MusicalNote>) {
        this.notes = newNotes
        if (newNotes.isNotEmpty()) {
            minPitch = newNotes.minOf { it.note } - 4
            maxPitch = newNotes.maxOf { it.note } + 4
            totalDurationMs = newNotes.maxOfOrNull { it.startTime + it.duration } ?: 10000L
        } 
        livePitches.clear()
        recordingStartTime = -1L
        requestLayout()
        invalidate()
    }

    fun addLivePitch(pitchInHz: Float) {
        if (recordingStartTime == -1L) {
            recordingStartTime = System.currentTimeMillis()
        }
        val elapsedTime = System.currentTimeMillis() - recordingStartTime

        // Convert frequency (Hz) to MIDI note number
        val midiNote = (69 + 12 * log2(pitchInHz / 440f))
        livePitches.add(Pair(elapsedTime, midiNote.toFloat()))
        
        // Redraw the view to show the new point
        invalidate()
    }

    fun clearLivePitches() {
        livePitches.clear()
        recordingStartTime = -1L
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val pitchRange = maxPitch - minPitch + 1
        val desiredHeight = (pitchRange * keyHeight).toInt() + timeAxisHeight.toInt()
        val desiredWidth = (totalDurationMs / 1000f * pixelsPerSecond).toInt() + pitchLabelWidth.toInt()

        val width = resolveSize(desiredWidth, widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGridAndLabels(canvas)
        drawNotes(canvas)
        drawLivePitches(canvas) // New function call
    }

    private fun drawGridAndLabels(canvas: Canvas) {
        val pitchRange = maxPitch - minPitch + 1
        val viewHeight = height - timeAxisHeight

        // ... (existing grid drawing logic) ...
        for (i in 0..pitchRange) {
            val y = viewHeight - (i * keyHeight)
            canvas.drawLine(pitchLabelWidth, y, width.toFloat(), y, gridPaint)
        }
        
        textPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..pitchRange) {
            val pitch = minPitch + i
            if (pitch < 0 || pitch > 127) continue

            val y = viewHeight - ((i - 0.5f) * keyHeight) + textPaint.textSize / 3
            val octave = (pitch / 12) - 1
            val noteName = pitchNames[pitch % 12]
            canvas.drawText("$noteName$octave", pitchLabelWidth - 15, y, textPaint)
        }

        val seconds = (totalDurationMs / 1000).toInt()
        textPaint.textAlign = Paint.Align.CENTER
        for (i in 0..seconds) {
            val x = pitchLabelWidth + (i * pixelsPerSecond)
            canvas.drawLine(x, 0f, x, viewHeight, gridPaint)
            canvas.drawText("${i}s", x, viewHeight + 40f, textPaint)
        }
    }

    private fun drawNotes(canvas: Canvas) {
        // ... (existing note drawing logic) ...
        val viewHeight = height - timeAxisHeight
        val pixelsPerMs = (width - pitchLabelWidth) / totalDurationMs.toFloat()

        for (note in notes) {
            val noteTop = viewHeight - ((note.note - minPitch + 1) * keyHeight)
            val noteLeft = pitchLabelWidth + (note.startTime * pixelsPerMs)
            val noteRight = noteLeft + (note.duration * pixelsPerMs)
            val noteBottom = noteTop + keyHeight

            notePaint.color = noteColors[note.note % noteColors.size]
            canvas.drawRect(noteLeft, noteTop, noteRight, noteBottom, notePaint)
        }
    }

    // New function to draw the live pitch data
    private fun drawLivePitches(canvas: Canvas) {
        val viewHeight = height - timeAxisHeight
        val pixelsPerMs = (width - pitchLabelWidth) / totalDurationMs.toFloat()
        
        if (livePitches.size < 2) return

        for (i in 0 until livePitches.size - 1) {
            val p1 = livePitches[i]
            val p2 = livePitches[i+1]

            val x1 = pitchLabelWidth + p1.first * pixelsPerMs
            val y1 = viewHeight - ((p1.second - minPitch) * keyHeight)

            val x2 = pitchLabelWidth + p2.first * pixelsPerMs
            val y2 = viewHeight - ((p2.second - minPitch) * keyHeight)

            // Draw a line between consecutive points
            canvas.drawLine(x1, y1, x2, y2, livePitchPaint)
        }
    }
}
