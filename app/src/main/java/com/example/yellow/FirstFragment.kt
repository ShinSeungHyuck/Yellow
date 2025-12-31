package com.example.yellow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.io.android.AudioDispatcherFactory
import be.tarsos.dsp.pitch.PitchDetectionHandler
import be.tarsos.dsp.pitch.PitchProcessor
import be.tarsos.dsp.pitch.PitchProcessor.PitchEstimationAlgorithm
import com.example.yellow.databinding.FragmentFirstBinding
import java.io.IOException

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private var dispatcher: AudioDispatcher? = null
    private val PERMISSION_REQUEST_CODE = 123

    // 라이브 피치 타임라인 시작 기준(상대시간 0점)
    private var liveStartElapsed: Long = 0L

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadMidiFile("placeholder.mid")

        binding.buttonStart.setOnClickListener {
            if (checkPermissions()) {
                startPitchDetection()
            } else {
                requestPermissions()
            }
        }

        binding.buttonStop.setOnClickListener {
            stopPitchDetection()
        }
    }

    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            requireActivity(),
            arrayOf(Manifest.permission.RECORD_AUDIO),
            PERMISSION_REQUEST_CODE
        )
    }

    private fun startPitchDetection() {
        if (dispatcher?.isStopped == false) {
            Log.d("FirstFragment", "Pitch detection is already running.")
            return
        }

        // (중요) 라이브 타임라인을 0으로 재시작
        liveStartElapsed = SystemClock.elapsedRealtime()
        binding.pianoRollView.resetLiveTimelineBase(liveStartElapsed)
        binding.pianoRollView.clearLivePitches()

        dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(22050, 1024, 0)

        val pitchDetectionHandler = PitchDetectionHandler { res, _ ->
            val pitchInHz = res.pitch

            // UI 스레드에서 View 업데이트
            activity?.runOnUiThread {
                if (pitchInHz > 0) {
                    // PianoRollView 내부에서 "상대시간(ms)"로 변환해서 추가하도록 변경됨
                    binding.pianoRollView.addLivePitch(pitchInHz)
                }
            }
        }

        val pitchProcessor = PitchProcessor(
            PitchEstimationAlgorithm.YIN,
            22050f,
            1024,
            pitchDetectionHandler
        )
        dispatcher?.addAudioProcessor(pitchProcessor)

        Thread(dispatcher, "Audio Dispatcher").start()
        Log.d("FirstFragment", "Started pitch detection.")
    }

    private fun stopPitchDetection() {
        dispatcher?.stop()
        dispatcher = null
        Log.d("FirstFragment", "Stopped pitch detection.")
    }

    private fun loadMidiFile(fileName: String) {
        try {
            val inputStream = requireContext().assets.open(fileName)

            // 주의: 여기 MidiParser API는 당신 프로젝트 원형에 맞춰야 합니다.
            // (현재 코드는 기존 구조를 유지)
            val midiParser = MidiParser()
            val notes = midiParser.parse(inputStream)

            binding.pianoRollView.setNotes(notes)
        } catch (e: IOException) {
            Log.e("FirstFragment", "Failed to load MIDI file: $fileName", e)
            binding.pianoRollView.setNotes(emptyList())
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startPitchDetection()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopPitchDetection()
        _binding = null
    }
}
