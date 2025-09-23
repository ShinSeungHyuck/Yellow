package com.example.yellow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class PitchView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val midiPaint = Paint()
    private val voicePaint = Paint()
    private var voicePitch = 0f
    private var midiNotes: List<Pair<Float, Int>> = emptyList()

    init {
        midiPaint.color = Color.BLUE
        midiPaint.strokeWidth = 5f

        voicePaint.color = Color.RED
        voicePaint.style = Paint.Style.FILL

        // MidiHelper를 사용하여 MIDI 노트 정보 가져오기
        val midiHelper = MidiHelper(context)
        midiNotes = midiHelper.getMidiNotes("song.mid")
    }

    fun setVoicePitch(pitch: Float) {
        voicePitch = pitch
        invalidate() // 뷰를 다시 그리도록 요청
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // MIDI 노트 그리기
        for (note in midiNotes) {
            val x = note.first * 100 // 시간 축
            val y = height - (note.second * 5) // 음고 축
            canvas.drawRect(x, y - 10, x + 50, y, midiPaint)
        }

        // 사용자 음고 그리기 (원)
        if (voicePitch > 0) {
            val y = height - (voicePitch * 5)
            canvas.drawCircle(width / 2f, y, 20f, voicePaint)
        }
    }
}
