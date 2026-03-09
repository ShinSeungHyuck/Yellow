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
    private val detectedPitchPaint = Paint()

    private var detectedPitch: Float? = null

    private val pitchNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    // PianoRollView와 동일한 timeAxisHeight로 맞춰 레이블 위치 정렬
    private val timeAxisHeight = 60f

    init {
        textPaint.color = Color.parseColor("#9AAABB")
        textPaint.textSize = 22f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isAntiAlias = true
        detectedPitchPaint.color = Color.parseColor("#3CD3FE")
        detectedPitchPaint.strokeWidth = 4f
    }

    fun setPitchRange(minPitch: Int, maxPitch: Int) {
        this.minPitch = minPitch
        this.maxPitch = maxPitch
        requestLayout()
        invalidate()
    }

    fun setDetectedPitch(pitch: Float) {
        this.detectedPitch = pitch
        invalidate()
    }

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
        val desiredWidth = (paddingLeft + paddingRight + textPaint.measureText("C#8") + 8).toInt()
        val measuredHeight = getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(desiredWidth, measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availableHeight = height - timeAxisHeight
        val pitchRange = (maxPitch - minPitch + 1).coerceAtLeast(1)
        val keyHeight = availableHeight / pitchRange

        // 겹치지 않도록 동적 간격 (최소 6음), 살짝 촘촘하게
        val stride = ((textPaint.textSize * 1.8f / keyHeight).toInt() + 1).coerceAtLeast(6)

        var pitch = minPitch
        while (pitch <= maxPitch) {
            val octave = pitch / 12 - 1
            val name = pitchNames[pitch % 12]
            val label = "$name$octave"

            val y = availableHeight - ((pitch - minPitch) * keyHeight) - keyHeight / 2f +
                    (textPaint.descent() - textPaint.ascent()) / 4f

            canvas.drawText(label, (width / 2).toFloat(), y, textPaint)
            pitch += stride
        }

        // 감지된 음정 표시선 (하늘색)
        detectedPitch?.let {
            val y = availableHeight - ((it - minPitch) * keyHeight)
            canvas.drawLine(0f, y, width.toFloat(), y, detectedPitchPaint)
        }
    }
}
