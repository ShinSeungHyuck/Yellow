package com.example.yellow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.View
import kotlin.math.max

class PianoRollView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var notes: List<MusicalNote> = emptyList()
    private val notePaint = Paint()
    private val gridPaint = Paint()
    private val textPaint = Paint()

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

    init {
        gridPaint.color = Color.parseColor("#e0e0e0")
        gridPaint.strokeWidth = 1f
        textPaint.color = Color.DKGRAY
        textPaint.textSize = 28f
        textPaint.isAntiAlias = true
    }

    fun setNotes(newNotes: List<MusicalNote>) {
        this.notes = newNotes
        if (newNotes.isNotEmpty()) {
            minPitch = newNotes.minOf { it.note } - 2
            maxPitch = newNotes.maxOf { it.note } + 2
            totalDurationMs = newNotes.maxOfOrNull { it.startTime + it.duration } ?: 10000L
            Log.d("PianoRollView", "Notes set. Pitch range: $minPitch-$maxPitch, Duration: ${totalDurationMs}ms")
        } else {
            Log.d("PianoRollView", "No notes provided. Using default view size.")
        }
        requestLayout() 
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val pitchRange = maxPitch - minPitch + 1
        val desiredHeight = (pitchRange * keyHeight).toInt() + timeAxisHeight.toInt()
        val desiredWidth = (totalDurationMs / 1000f * pixelsPerSecond).toInt() + pitchLabelWidth.toInt()

        val width = resolveSize(desiredWidth, widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
        Log.d("PianoRollView", "onMeasure: Measured dimension set to ${width}x${height} (Desired: ${desiredWidth}x${desiredHeight})")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        Log.d("PianoRollView", "onDraw called. Drawing ${notes.size} notes.")

        drawGridAndLabels(canvas)
        if (notes.isNotEmpty()) {
            drawNotes(canvas)
        }
    }

    private fun drawGridAndLabels(canvas: Canvas) {
        val pitchRange = maxPitch - minPitch + 1
        val viewHeight = height - timeAxisHeight

        // Draw horizontal lines for each pitch
        for (i in 0..pitchRange) {
            val y = viewHeight - (i * keyHeight)
            canvas.drawLine(pitchLabelWidth, y, width.toFloat(), y, gridPaint)
        }
        
        // Draw pitch names (C4, G#5 etc.) on the left
        textPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..pitchRange) {
            val pitch = minPitch + i
            if (pitch < 0 || pitch > 127) continue

            val y = viewHeight - ((i - 0.5f) * keyHeight) + textPaint.textSize / 3
            val octave = (pitch / 12) - 1
            val noteName = pitchNames[pitch % 12]
            canvas.drawText("$noteName$octave", pitchLabelWidth - 15, y, textPaint)
        }

        // Draw vertical lines and time labels
        val seconds = (totalDurationMs / 1000).toInt()
        textPaint.textAlign = Paint.Align.CENTER
        for (i in 0..seconds) {
            val x = pitchLabelWidth + (i * pixelsPerSecond)
            canvas.drawLine(x, 0f, x, viewHeight, gridPaint)
            canvas.drawText("${i}s", x, viewHeight + 40f, textPaint)
        }
    }

    private fun drawNotes(canvas: Canvas) {
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
}
