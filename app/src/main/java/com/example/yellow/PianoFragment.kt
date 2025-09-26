package com.example.yellow

import android.os.Bundle
import android.util.Log
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

        // IMPORTANT: Replace "placeholder.mid" with the actual name of your midi file in the assets folder.
        loadMidiFile("placeholder.mid")
    }

    private fun loadMidiFile(fileName: String) {
        try {
            Log.d("PianoFragment", "Attempting to load MIDI file: $fileName")
            // 1. Open the MIDI file from the assets folder
            val inputStream = requireContext().assets.open(fileName)

            // 2. Parse the MIDI file using our custom parser
            val midiParser = MidiParser()
            val notes = midiParser.parse(inputStream)

            if (notes.isEmpty()) {
                Log.w("PianoFragment", "MIDI parsing resulted in 0 notes. Check the MIDI file or parser logic.")
            } else {
                Log.d("PianoFragment", "Successfully parsed ${notes.size} notes.")
            }

            // 3. Set the parsed notes to our PianoRollView
            binding.pianoRollView.setNotes(notes)

        } catch (e: IOException) {
            Log.e("PianoFragment", "Failed to load MIDI file '$fileName' from assets. Make sure the file exists.", e)
            // Even if the file fails to load, set empty notes to draw the grid
            binding.pianoRollView.setNotes(emptyList())
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
