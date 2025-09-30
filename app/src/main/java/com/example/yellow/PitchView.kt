package com.example.yellow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class PitchView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var minPitch = 48
    private var maxPitch = 72
    private val textPaint = Paint()
    private val gridPaint = Paint()

    private val pitchNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val timeAxisHeight = 60f // PianoRollView와 동일한 값

    init {
        textPaint.color = Color.BLACK
        textPaint.textSize = 28f
        textPaint.textAlign = Paint.Align.CENTER
        gridPaint.color = Color.LTGRAY
        gridPaint.strokeWidth = 1f
    }

    fun setPitchRange(minPitch: Int, maxPitch: Int) {
        this.minPitch = minPitch
        this.maxPitch = maxPitch
        requestLayout() // 범위가 변경되었으므로 레이아웃을 다시 계산합니다.
        invalidate() // 다시 그립니다.
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

            // 키의 중앙에 텍스트를 그립니다.
            val y = availableHeight - ((pitch - minPitch) * keyHeight) - (keyHeight / 2) + (textPaint.descent() - textPaint.ascent()) / 4

            // 가로선을 그립니다.
            val lineY = availableHeight - ((pitch - minPitch) * keyHeight)
            canvas.drawLine(0f, lineY, width.toFloat(), lineY, gridPaint)

            canvas.drawText(pitchLabel, (width / 2).toFloat(), y, textPaint)
        }
    }
}
