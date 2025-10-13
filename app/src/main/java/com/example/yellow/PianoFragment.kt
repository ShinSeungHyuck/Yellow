package com.example.yellow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.example.yellow.databinding.FragmentPianoBinding
import java.io.IOException

class PianoFragment : Fragment() {

    private var _binding: FragmentPianoBinding? = null
    private val binding get() = _binding!!

    private var dispatcher: AudioDispatcher? = null
    private val PERMISSION_REQUEST_CODE = 124

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPianoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadMidiFile("placeholder.mid")

        binding.startButton.setOnClickListener {
            if (checkPermissions()) {
                startPitchDetection()
            } else {
                requestPermissions()
            }
        }

        binding.stopButton.setOnClickListener {
            stopPitchDetection()
        }
    }

    private fun startPitchDetection() {
        if (dispatcher?.isStopped == false) {
            Log.d("PianoFragment", "Pitch detection is already running.")
            return
        }
        
        binding.pianoRollView.clearLivePitches()
        binding.pitchView.clearDetectedPitch()

        dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(22050, 1024, 0)

        val pitchDetectionHandler = PitchDetectionHandler { res, e ->
            val pitchInHz = res.pitch
            activity?.runOnUiThread {
                if (pitchInHz > 0) {
                    binding.pitchView.setDetectedPitch(pitchInHz)
                    binding.pianoRollView.addLivePitch(pitchInHz)
                }
            }
        }

        val pitchProcessor = PitchProcessor(
            PitchProcessor.PitchEstimationAlgorithm.YIN,
            22050f,
            1024,
            pitchDetectionHandler
        )
        dispatcher?.addAudioProcessor(pitchProcessor)

        Thread(dispatcher, "Audio Dispatcher").start()
        Log.d("PianoFragment", "Started pitch detection.")
    }

    private fun stopPitchDetection() {
        dispatcher?.stop()
        binding.pitchView.clearDetectedPitch()
        binding.pianoRollView.clearLivePitches()
        Log.d("PianoFragment", "Stopped pitch detection.")
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

    private fun loadMidiFile(fileName: String) {
        try {
            Log.d("PianoFragment", "Attempting to load MIDI file: $fileName")
            val inputStream = requireContext().assets.open(fileName)
            val midiParser = MidiParser()
            val notes = midiParser.parse(inputStream)

            if (notes.isEmpty()) {
                Log.w("PianoFragment", "MIDI parsing resulted in 0 notes. Check the MIDI file or parser logic.")
            } else {
                Log.d("PianoFragment", "Successfully parsed ${notes.size} notes.")
            }

            binding.pianoRollView.setNotes(notes)
            binding.pitchView.setPitchRange(
                binding.pianoRollView.currentMinPitch,
                binding.pianoRollView.currentMaxPitch
            )

        } catch (e: IOException) {
            Log.e("PianoFragment", "Failed to load MIDI file '$fileName' from assets. Make sure the file exists.", e)
            binding.pianoRollView.setNotes(emptyList())
            binding.pitchView.setPitchRange(48, 72)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopPitchDetection()
        _binding = null
    }
}
