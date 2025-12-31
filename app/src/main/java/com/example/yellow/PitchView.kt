package com.example.yellow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.log2

class PitchView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var minPitch = 48
    private var maxPitch = 72
    private val textPaint = Paint()
    private val gridPaint = Paint()
    private val detectedPitchPaint = Paint()

    private var detectedPitch: Float? = null

    private val pitchNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val timeAxisHeight = 40f

    init {
        textPaint.color = Color.BLACK
        textPaint.textSize = 20f
        textPaint.textAlign = Paint.Align.CENTER
        gridPaint.color = Color.LTGRAY
        gridPaint.strokeWidth = 1f
        detectedPitchPaint.color = Color.RED
        detectedPitchPaint.strokeWidth = 5f
    }

    fun setPitchRange(minPitch: Int, maxPitch: Int) {
        this.minPitch = minPitch
        this.maxPitch = maxPitch
        requestLayout()
        invalidate()
    }

    /** 기존 사용(= MIDI note float로 들어오는 경우) */
    fun setDetectedPitch(pitch: Float) {
        this.detectedPitch = pitch
        invalidate()
    }

    /** (중요) PianoFragment에서 setPitchHz를 부르면 여기가 받아서 변환해줌 */
    fun setPitchHz(pitchHz: Float) {
        if (pitchHz <= 0f) {
            clearDetectedPitch()
            return
        }
        val midi = (69 + 12 * log2(pitchHz / 440f)).toFloat()
        setDetectedPitch(midi)
    }

    fun clearDetectedPitch() {
        this.detectedPitch = null
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (paddingLeft + paddingRight + textPaint.measureText("C#8")).toInt()
        val measuredHeight = getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(desiredWidth, measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availableHeight = height - timeAxisHeight
        val pitchRange = (maxPitch - minPitch).coerceAtLeast(1)
        val keyHeight = availableHeight / (pitchRange + 1)

        for (pitch in minPitch..maxPitch) {
            val octave = pitch / 12 - 1
            val name = pitchNames[pitch % 12]
            val pitchLabel = "$name$octave"

            val y = availableHeight - ((pitch - minPitch) * keyHeight) - (keyHeight / 2) +
                    (textPaint.descent() - textPaint.ascent()) / 4

            val lineY = availableHeight - ((pitch - minPitch) * keyHeight)
            canvas.drawLine(0f, lineY, width.toFloat(), lineY, gridPaint)

            canvas.drawText(pitchLabel, (width / 2).toFloat(), y, textPaint)
        }

        detectedPitch?.let {
            val y = availableHeight - ((it - minPitch) * keyHeight)
            canvas.drawLine(0f, y, width.toFloat(), y, detectedPitchPaint)
        }
    }
}
