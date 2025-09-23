package com.example.yellow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class PitchView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private val notePaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.FILL
    }

    private val userPitchPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    private var notes: List<MusicalNote> = emptyList()
    private var userPitches: List<Float> = emptyList()

    fun setNotes(notes: List<MusicalNote>) {
        this.notes = notes
        invalidate()
    }

    fun addUserPitch(pitch: Float) {
        userPitches = userPitches + pitch
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw musical notes
        for (note in notes) {
            val left = note.start / 100f
            val right = (note.start + note.duration) / 100f
            val top = (127 - note.pitch) * height / 127f
            val bottom = top + 10f
            canvas.drawRect(left, top, right, bottom, notePaint)
        }

        // Draw user's pitch
        for ((index, pitch) in userPitches.withIndex()) {
            val x = index * 10f
            val y = (127 - pitch) * height / 127f
            canvas.drawCircle(x, y, 5f, userPitchPaint)
        }
    }
}
