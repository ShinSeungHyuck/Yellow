package com.example.yellow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.AudioProcessor
import be.tarsos.dsp.io.android.AudioDispatcherFactory
import be.tarsos.dsp.pitch.PitchDetectionHandler
import be.tarsos.dsp.pitch.PitchDetectionResult
import be.tarsos.dsp.pitch.PitchProcessor
import kotlin.math.log2
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var buttonStartStop: Button
    private lateinit var textViewPitch: TextView
    private lateinit var textViewNote: TextView

    private var dispatcher: AudioDispatcher? = null
    private var audioThread: Thread? = null
    private var isRecording = false
    private val RECORD_AUDIO_PERMISSION_CODE = 1

    private val NOTES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        buttonStartStop = findViewById(R.id.buttonStartStop)
        textViewPitch = findViewById(R.id.textViewPitch)
        textViewNote = findViewById(R.id.textViewNote)

        buttonStartStop.setOnClickListener {
            if (isRecording) stopRecording()
            else startRecording()
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION_CODE
            )
        } else {
            initAudioDispatcher()
            audioThread = Thread(dispatcher, "Audio Dispatcher")
            audioThread?.start()
            isRecording = true
            buttonStartStop.text = "중지"
            textViewPitch.text = "음고: 분석 중..."
            textViewNote.text = "음이름: -"
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
        buttonStartStop.text = "시작"
        textViewPitch.text = "음고: -"
        textViewNote.text = "음이름: -"
    }

    private fun initAudioDispatcher() {
        val sampleRate = 22050
        val bufferSize = 1024
        val bufferOverlap = 0

        dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(sampleRate, bufferSize, bufferOverlap)

        val handler = PitchDetectionHandler { result, _ ->
            runOnUiThread { handlePitch(result) }
        }

        val pitchProcessor: AudioProcessor = PitchProcessor(
            PitchProcessor.PitchEstimationAlgorithm.YIN,
            sampleRate.toFloat(),
            bufferSize,
            handler
        )
        dispatcher?.addAudioProcessor(pitchProcessor)
    }

    private fun handlePitch(result: PitchDetectionResult) {
        val pitch = result.pitch
        if (pitch != -1f) {
            textViewPitch.text = String.format("음고: %.2f Hz", pitch)
            textViewNote.text = String.format("음이름: %s", pitchToNote(pitch))
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
                textViewPitch.text = "음고: 마이크 권한 필요"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
    }
}
