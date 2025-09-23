package com.example.yellow

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.yellow.databinding.FragmentPianoBinding
import java.io.IOException

class PianoFragment : Fragment() {

    private var _binding: FragmentPianoBinding? = null
    private val binding get() = _binding!!

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

        // You should replace "placeholder.mid" with the actual name of your midi file in the assets
        loadMidiFile("your_midi_file.mid")
    }

    private fun loadMidiFile(fileName: String) {
        try {
            // 1. Open the MIDI file from the assets folder
            val inputStream = requireContext().assets.open(fileName)

            // 2. Parse the MIDI file using our custom parser
            val midiParser = MidiParser()
            val notes = midiParser.parse(inputStream)

            // 3. Set the parsed notes to our PianoRollView
            binding.pianoRollView.setNotes(notes)

        } catch (e: IOException) {
            // Handle file not found or other IO errors
            e.printStackTrace()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
