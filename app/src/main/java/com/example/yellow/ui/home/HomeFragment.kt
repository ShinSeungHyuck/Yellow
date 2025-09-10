package com.example.yellow.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.AudioProcessor
import be.tarsos.dsp.io.android.AudioDispatcherFactory
import be.tarsos.dsp.pitch.PitchDetectionHandler
import be.tarsos.dsp.pitch.PitchDetectionResult
import be.tarsos.dsp.pitch.PitchProcessor
import com.example.yellow.databinding.FragmentHomeBinding
import kotlin.math.log2
import kotlin.math.roundToInt

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var dispatcher: AudioDispatcher? = null
    private var audioThread: Thread? = null
    private var isRecording = false

    private val RECORD_AUDIO_PERMISSION_CODE = 1
    private val NOTES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.buttonStartStop.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION_CODE
            )
        } else {
            initAudioDispatcher()
            audioThread = Thread(dispatcher, "Audio Dispatcher")
            audioThread?.start()
            isRecording = true
            binding.buttonStartStop.text = "중지"
            binding.textViewPitch.text = "음고: 분석 중..."
            binding.textViewNote.text = "음이름: -"
        }
    }

    private fun stopRecording() {
        dispatcher?.stop()
        audioThread?.interrupt()
        try {
            audioThread?.join(500)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        dispatcher = null
        audioThread = null
        isRecording = false
        binding.buttonStartStop.text = "시작"
        binding.textViewPitch.text = "음고: -"
        binding.textViewNote.text = "음이름: -"
    }

    private fun initAudioDispatcher() {
        val sampleRate = 22050
        val audioBufferSize = 1024
        val bufferOverlap = 0

        dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(sampleRate, audioBufferSize, bufferOverlap)

        val pitchDetectionHandler = PitchDetectionHandler { result, audioEvent ->
            activity?.runOnUiThread {
                handlePitch(result, audioEvent)
            }
        }

        val pitchProcessor: AudioProcessor = PitchProcessor(
            PitchProcessor.PitchEstimationAlgorithm.YIN,
            sampleRate.toFloat(),
            audioBufferSize,
            pitchDetectionHandler
        )
        dispatcher?.addAudioProcessor(pitchProcessor)
    }

    private fun handlePitch(result: PitchDetectionResult, audioEvent: AudioEvent) {
        val pitchInHz = result.pitch
        if (pitchInHz != -1f) {
            binding.textViewPitch.text = String.format("음고: %.2f Hz", pitchInHz)
            binding.textViewNote.text = String.format("음이름: %s", pitchToNote(pitchInHz))
        }
    }

    private fun pitchToNote(frequency: Float): String {
        if (frequency <= 0) return "-"

        val midiNote = (12 * log2(frequency / 440f) + 69).roundToInt()
        if (midiNote < 0 || midiNote > 127) return "-"

        val noteIndex = midiNote % 12
        val octave = midiNote / 12 - 1
        return NOTES[noteIndex] + octave
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                binding.textViewPitch.text = "음고: 마이크 권한 필요"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopRecording()
        _binding = null
    }
}
