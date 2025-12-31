package com.example.yellow

import android.util.Log
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.io.android.AudioDispatcherFactory
import be.tarsos.dsp.pitch.PitchDetectionHandler
import be.tarsos.dsp.pitch.PitchProcessor
import java.util.concurrent.atomic.AtomicBoolean

class VoicePitchDetector(
    private val onPitch: (hz: Float, probability: Float) -> Unit
) {

    private var dispatcher: AudioDispatcher? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    private val sampleRate = 22050
    private val bufferSize = 1024
    private val overlap = 0

    fun start() {
        if (running.getAndSet(true)) {
            Log.d(TAG, "VoicePitchDetector already running")
            return
        }

        try {
            // ✅ 당신 프로젝트의 TarsosDSP 버전에서는 3-인자만 지원
            dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(
                sampleRate,
                bufferSize,
                overlap
            )

            val handler = PitchDetectionHandler { res, _ ->
                onPitch(res.pitch, res.probability)
            }

            val processor = PitchProcessor(
                PitchProcessor.PitchEstimationAlgorithm.YIN,
                sampleRate.toFloat(),
                bufferSize,
                handler
            )

            dispatcher?.addAudioProcessor(processor)

            thread = Thread(dispatcher, "VoicePitchDetectorThread").apply { start() }
            Log.d(TAG, "VoicePitchDetector started")
        } catch (e: Exception) {
            running.set(false)
            Log.e(TAG, "Failed to start VoicePitchDetector", e)
            stop()
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            dispatcher?.stop()
        } catch (_: Exception) {
        } finally {
            dispatcher = null
            thread = null
            Log.d(TAG, "VoicePitchDetector stopped")
        }
    }

    companion object {
        private const val TAG = "VoicePitchDetector"
    }
}
