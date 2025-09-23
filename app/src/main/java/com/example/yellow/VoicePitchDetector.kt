package com.example.yellow

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread

class VoicePitchDetector(private val onPitchDetected: (Float) -> Unit) {

    private var isRecording = false
    private lateinit var audioRecord: AudioRecord
    private val sampleRate = 44100
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        isRecording = true
        audioRecord.startRecording()

        thread {
            val buffer = ShortArray(bufferSize)
            while (isRecording) {
                val read = audioRecord.read(buffer, 0, bufferSize)
                if (read > 0) {
                    val pitch = findFundamentalFrequency(buffer)
                    onPitchDetected(pitch)
                }
            }
        }
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false
        audioRecord.stop()
        audioRecord.release()
    }

    private fun findFundamentalFrequency(audioBuffer: ShortArray): Float {
        // A simple auto-correlation based pitch detection
        val n = audioBuffer.size
        val autoCorrelation = IntArray(n)

        for (lag in 0 until n) {
            var sum = 0
            for (i in 0 until n - lag) {
                sum += audioBuffer[i] * audioBuffer[i + lag]
            }
            autoCorrelation[lag] = sum
        }

        var d = 0
        while (d < n - 1 && autoCorrelation[d] > autoCorrelation[d + 1]) {
            d++
        }

        var maxPeak = d
        while (d < n - 1) {
            if (autoCorrelation[d] > autoCorrelation[maxPeak] && autoCorrelation[d] > autoCorrelation[d - 1] && autoCorrelation[d] > autoCorrelation[d + 1]) {
                maxPeak = d
            }
            d++
        }

        val T = maxPeak
        return if (T > 0) sampleRate.toFloat() / T else 0f
    }
}
