package com.example.yellow

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
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
import io.supabase.kt.createSupabaseClient
import io.supabase.kt.storage.storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URL

class PianoFragment : Fragment() {

    private var _binding: FragmentPianoBinding? = null
    private val binding get() = _binding!!

    private var dispatcher: AudioDispatcher? = null
    private val PERMISSION_REQUEST_CODE = 124
    private var mediaPlayer: MediaPlayer? = null

    private val supabase by lazy {
        createSupabaseClient(
            supabaseUrl = "https://zwwoqjumejiouapcoxix.supabase.co",
            supabaseKey = "sb_publishable_1wgwzjZe0isfv4sacAdtMA_amakpsBV"
        )
    }

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

        loadMidiFromUrl("https://zwwoqjumejiouapcoxix.supabase.co/storage/v1/object/public/songs/midi/0a7eff49-11ea-4a08-90b2-ddbe257b6e19.mid")
        prepareMediaPlayer("https://zwwoqjumejiouapcoxix.supabase.co/storage/v1/object/public/songs/melody/0a7eff49-11ea-4a08-90b2-ddbe257b6e19.mp3")

        binding.startButton.setOnClickListener {
            if (checkPermissions()) {
                startPitchDetection()
                mediaPlayer?.start()
            } else {
                requestPermissions()
            }
        }

        binding.stopButton.setOnClickListener {
            stopPitchDetection()
            mediaPlayer?.pause()
            mediaPlayer?.seekTo(0)
        }

        binding.loadMidiButton.setOnClickListener {
            val fileName = binding.midiFileNameInput.text.toString()
            if (fileName.isNotEmpty()) {
                loadMidiFromSupabase(fileName)
            }
        }
    }

    private fun prepareMediaPlayer(url: String) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(url)
            prepareAsync()
            setOnPreparedListener {
                Log.d("PianoFragment", "MediaPlayer prepared")
            }
            setOnErrorListener { mp, what, extra ->
                Log.e("PianoFragment", "MediaPlayer error: what=$what, extra=$extra")
                true
            }
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
                mediaPlayer?.start()
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

    private fun loadMidiFromSupabase(fileName: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("PianoFragment", "Attempting to download MIDI file from Supabase: $fileName")
                val bucket = supabase.storage.from("songs")
                val data = bucket.downloadPublic("midi/$fileName")
                val inputStream = ByteArrayInputStream(data)
                val midiParser = MidiParser()
                val notes = midiParser.parse(inputStream)

                activity?.runOnUiThread {
                    if (notes.isEmpty()) {
                        Log.w("PianoFragment", "MIDI parsing resulted in 0 notes. Check the MIDI file or parser logic.")
                    } else {
                        Log.d("PianoFragment", "Successfully parsed ${notes.size} notes from Supabase.")
                    }

                    binding.pianoRollView.setNotes(notes)
                    binding.pitchView.setPitchRange(
                        binding.pianoRollView.currentMinPitch,
                        binding.pianoRollView.currentMaxPitch
                    )
                }
            } catch (e: Exception) {
                Log.e("PianoFragment", "Failed to load MIDI file from Supabase.", e)
                activity?.runOnUiThread {
                    binding.pianoRollView.setNotes(emptyList())
                    binding.pitchView.setPitchRange(48, 72)
                }
            }
        }
    }

    private fun loadMidiFromUrl(url: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("PianoFragment", "Attempting to download MIDI file from URL: $url")
                val inputStream = URL(url).openStream()
                val midiParser = MidiParser()
                val notes = midiParser.parse(inputStream)

                activity?.runOnUiThread {
                    if (notes.isEmpty()) {
                        Log.w("PianoFragment", "MIDI parsing resulted in 0 notes. Check the MIDI file or parser logic.")
                    } else {
                        Log.d("PianoFragment", "Successfully parsed ${notes.size} notes from URL.")
                    }

                    binding.pianoRollView.setNotes(notes)
                    binding.pitchView.setPitchRange(
                        binding.pianoRollView.currentMinPitch,
                        binding.pianoRollView.currentMaxPitch
                    )
                }
            } catch (e: Exception) {
                Log.e("PianoFragment", "Failed to load MIDI file from URL.", e)
                activity?.runOnUiThread {
                    binding.pianoRollView.setNotes(emptyList())
                    binding.pitchView.setPitchRange(48, 72)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopPitchDetection()
        mediaPlayer?.release()
        mediaPlayer = null
        _binding = null
    }
}
