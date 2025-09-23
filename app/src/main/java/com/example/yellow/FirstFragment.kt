package com.example.yellow

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.yellow.databinding.FragmentFirstBinding

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private lateinit var voicePitchDetector: VoicePitchDetector
    private lateinit var midiParser: MidiParser

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        voicePitchDetector = VoicePitchDetector { pitch ->
            activity?.runOnUiThread {
                binding.pitchView.addUserPitch(pitch)
            }
        }

        midiParser = MidiParser()

        binding.startButton.setOnClickListener {
            // In a real application, you would load a MIDI file from storage.
            // For this example, we are using a placeholder MIDI file.
            val inputStream = context?.assets?.open("placeholder.mid")
            val notes = midiParser.parse(inputStream!!)
            binding.pitchView.setNotes(notes)
            voicePitchDetector.start()
        }

        binding.stopButton.setOnClickListener {
            voicePitchDetector.stop()
            binding.pitchView.clear()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        voicePitchDetector.stop()
        _binding = null
    }
}
