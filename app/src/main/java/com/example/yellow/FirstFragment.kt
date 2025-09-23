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
                binding.pitchView.setVoicePitch(pitch)
            }
        }

        binding.startButton.setOnClickListener {
            voicePitchDetector.start()
        }

        binding.stopButton.setOnClickListener {
            voicePitchDetector.stop()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        voicePitchDetector.stop()
        _binding = null
    }
}
