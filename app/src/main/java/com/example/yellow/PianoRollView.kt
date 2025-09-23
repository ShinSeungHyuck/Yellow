package com.example.yellow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
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

    private var minPitch = 60
    private var maxPitch = 72
    private var totalDurationMs = 5000L
    private val keyWidth = 80f
    private val timeAxisHeight = 60f

    init {
        gridPaint.color = Color.LTGRAY
        gridPaint.strokeWidth = 1f
        textPaint.color = Color.BLACK
        textPaint.textSize = 24f
    }

    fun setNotes(newNotes: List<MusicalNote>) {
        if (newNotes.isNotEmpty()) {
            minPitch = newNotes.minOf { it.note } - 1
            maxPitch = newNotes.maxOf { it.note } + 1
            totalDurationMs = newNotes.maxOfOrNull { it.startTime + it.duration } ?: 5000L
        }
        this.notes = newNotes
        requestLayout() // Request a re-measure and re-draw
        invalidate() // Redraw the view
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val pitchRange = maxPitch - minPitch + 1
        val desiredHeight = (pitchRange * keyWidth).toInt() + timeAxisHeight.toInt()

        // For width, let's make it scrollable, so we'll estimate a width based on duration
        val pixelsPerSecond = 200 // Adjust this to control horizontal zoom
        val desiredWidth = (totalDurationMs / 1000f * pixelsPerSecond).toInt()

        val width = resolveSize(desiredWidth, widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (notes.isEmpty()) return

        drawGridAndLabels(canvas)
        drawNotes(canvas)
    }

    private fun drawGridAndLabels(canvas: Canvas) {
        val pitchRange = maxPitch - minPitch + 1

        // Draw horizontal lines and pitch names
        for (i in 0..pitchRange) {
            val y = i * keyWidth
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)

            val pitch = maxPitch - i
            val octave = (pitch / 12) - 1
            val noteName = pitchNames[pitch % 12]
            canvas.drawText("$noteName$octave", 10f, y - 10f, textPaint)
        }

        // Draw vertical lines and time labels
        val pixelsPerSecond = width / (totalDurationMs / 1000f)
        val seconds = (totalDurationMs / 1000).toInt()
        for (i in 0..seconds) {
            val x = i * pixelsPerSecond
            canvas.drawLine(x, 0f, x, height.toFloat() - timeAxisHeight, gridPaint)
            canvas.drawText("${i}s", x + 5, height.toFloat() - 20, textPaint)
        }
    }

    private fun drawNotes(canvas: Canvas) {
        val pixelsPerMs = width / totalDurationMs.toFloat()

        for (note in notes) {
            val noteY = (maxPitch - note.note) * keyWidth
            val noteX = note.startTime * pixelsPerMs
            val noteWidth = max(note.duration * pixelsPerMs, 1f) // Ensure at least 1 pixel width

            notePaint.color = noteColors[note.note % noteColors.size]

            canvas.drawRect(noteX, noteY, noteX + noteWidth, noteY + keyWidth, notePaint)
        }
    }
}
