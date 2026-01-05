package com.example.yellow

import android.util.Log
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.io.android.AudioDispatcherFactory
import be.tarsos.dsp.pitch.PitchDetectionHandler
import be.tarsos.dsp.pitch.PitchProcessor
import kotlin.math.log10
import kotlin.math.sqrt

class VoicePitchDetector(
    private val onFrame: (hz: Float, prob: Float, rmsDb: Float) -> Unit
) {

    companion object {
        private const val TAG = "VoicePitchDetector"

        private const val SAMPLE_RATE = 22050
        private const val BUFFER_SIZE = 1024
        private const val OVERLAP = 0
    }

    private var dispatcher: AudioDispatcher? = null
    private var thread: Thread? = null

    fun start() {
        if (dispatcher != null) return

        dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(
            SAMPLE_RATE,
            BUFFER_SIZE,
            OVERLAP
        )

        val handler = PitchDetectionHandler { res, audioEvent ->
            val hz = res.pitch
            val prob = res.probability

            val buffer = audioEvent.floatBuffer
            var sumSq = 0.0
            for (v in buffer) sumSq += (v * v).toDouble()

            val rms = sqrt(sumSq / buffer.size.coerceAtLeast(1))
            val rmsDb = if (rms > 1e-9) (20.0 * log10(rms)).toFloat() else -120.0f

            onFrame(hz, prob, rmsDb)
        }

        val pitchProcessor = PitchProcessor(
            PitchProcessor.PitchEstimationAlgorithm.YIN,
            SAMPLE_RATE.toFloat(),
            BUFFER_SIZE,
            handler
        )

        dispatcher?.addAudioProcessor(pitchProcessor)

        thread = Thread(dispatcher, "AudioDispatcher").apply { start() }
        Log.d(TAG, "started")
    }

    fun stop() {
        try {
            dispatcher?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "stop error", e)
        } finally {
            dispatcher = null
            thread = null
            Log.d(TAG, "stopped")
        }
    }
}
