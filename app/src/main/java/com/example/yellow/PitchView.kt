package com.example.yellow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class PitchView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val textPaint = Paint()
    private val gridPaint = Paint()
    private var minPitch = 48
    private var maxPitch = 72
    private val keyHeight = 40f
    private val pitchLabelWidth = 120f
    private val pitchNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")


    init {
        textPaint.color = Color.DKGRAY
        textPaint.textSize = 28f
        textPaint.isAntiAlias = true
        textPaint.textAlign = Paint.Align.RIGHT
        gridPaint.color = Color.LTGRAY
        gridPaint.strokeWidth = 1f
    }

    fun setPitchRange(minPitch: Int, maxPitch: Int) {
        this.minPitch = minPitch
        this.maxPitch = maxPitch
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = pitchLabelWidth.toInt()
        val pitchRange = maxPitch - minPitch + 1
        val desiredHeight = (pitchRange * keyHeight).toInt()
        setMeasuredDimension(desiredWidth, desiredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pitchRange = maxPitch - minPitch
        val viewHeight = height

        for (i in 0..pitchRange) {
            val y = viewHeight - (i * keyHeight) - (keyHeight/2)
            val currentPitch = minPitch + i

            if (currentPitch < 0 || currentPitch > 127) continue

            // Draw horizontal line
            canvas.drawLine(0f, y + (keyHeight/2), width.toFloat(), y + (keyHeight/2), gridPaint)

            val octave = (currentPitch / 12) - 1
            val noteName = pitchNames[currentPitch % 12]
            canvas.drawText("$noteName$octave", pitchLabelWidth - 15, y + textPaint.textSize / 3, textPaint)
        }
    }
}