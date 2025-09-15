package com.example.yellow.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.io.android.AudioDispatcherFactory
import be.tarsos.dsp.pitch.PitchDetectionHandler
import be.tarsos.dsp.pitch.PitchProcessor

class HomeFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // fragment layout
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    private fun startPitchDetection() {
        val sampleRate = 22050
        val bufferSize = 1024
        val overlap = 512

        val dispatcher: AudioDispatcher =
            AudioDispatcherFactory.fromDefaultMicrophone(sampleRate, bufferSize, overlap)

        val handler = PitchDetectionHandler { res, event ->
            val pitchInHz = res.pitch
            // pitch 처리
        }

        val processor = PitchProcessor(
            PitchProcessor.PitchEstimationAlgorithm.YIN,
            sampleRate.toFloat(),
            bufferSize,
            handler
        )

        dispatcher.addAudioProcessor(processor)

        Thread(dispatcher, "Audio Dispatcher").start()
    }
}
