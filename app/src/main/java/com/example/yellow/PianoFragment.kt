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
import com.example.yellow.databinding.FragmentPianoBinding
import java.io.IOException

class PianoFragment : Fragment() {

    private var _binding: FragmentPianoBinding? = null
    private val binding get() = _binding!!

    private lateinit var voicePitchDetector: VoicePitchDetector
    private val PERMISSION_REQUEST_CODE = 124 // Use a different code to avoid conflicts

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

        voicePitchDetector = VoicePitchDetector { pitch ->
            activity?.runOnUiThread {
                binding.pitchView.setDetectedPitch(pitch)
            }
        }

        binding.startButton.setOnClickListener {
            if (checkPermissions()) {
                voicePitchDetector.start()
            } else {
                requestPermissions()
            }
        }

        binding.stopButton.setOnClickListener {
            voicePitchDetector.stop()
            binding.pitchView.clearDetectedPitch()
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                voicePitchDetector.start()
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
        voicePitchDetector.stop()
        _binding = null
    }
}
