package com.example.yellow // 자신의 패키지 이름으로 변경하세요.

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.text
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.AudioProcessor
import be.tarsos.dsp.io.android.AudioDispatcherFactory
import be.tarsos.dsp.pitch.PitchDetectionHandler
import be.tarsos.dsp.pitch.PitchDetectionResult
import be.tarsos.dsp.pitch.PitchProcessor
import java.lang.Math.log // Math.log 대신 kotlin.math.log2 사용 권장

// kotlin.math.log2 를 사용하기 위해 추가 (더 정확한 음이름 계산 가능)
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.text.format
import kotlin.text.isNotEmpty
import kotlin.text.toFloat


class MainActivity : AppCompatActivity() {

    private lateinit var buttonStartStop: Button
    private lateinit var textViewPitch: TextView
    private lateinit var textViewNote: TextView

    private var dispatcher: AudioDispatcher? = null
    private var audioThread: java.lang.Thread? = null
    private var isRecording = false

    private val RECORD_AUDIO_PERMISSION_CODE = 1

    // 음이름 계산을 위한 배열
    private val NOTES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // activity_main.xml 레이아웃 사용

        buttonStartStop = findViewById(R.id.buttonStartStop)
        textViewPitch = findViewById(R.id.textViewPitch)
        textViewNote = findViewById(R.id.textViewNote)

        buttonStartStop.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
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
        audioThread?.interrupt() // 스레드 인터럽트 시도
        try {
            audioThread?.join(500) // 스레드가 종료될 때까지 최대 0.5초 대기
        } catch (e: java.lang.InterruptedException) {
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
        // 샘플 레이트, 오디오 버퍼 사이즈, 버퍼 오버랩 설정
        val sampleRate = 22050 // TarsosDSP 기본 샘플 레이트 중 하나
        val audioBufferSize = 1024
        val bufferOverlap = 0 // 오버랩 없음

        dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(sampleRate, audioBufferSize, bufferOverlap)

        val pitchDetectionHandler = PitchDetectionHandler { result, audioEvent ->
            runOnUiThread { // UI 업데이트는 메인 스레드에서
                handlePitch(result, audioEvent)
            }
        }

        val pitchProcessor: AudioProcessor = PitchProcessor(
            PitchProcessor.PitchEstimationAlgorithm.YIN, // 또는 다른 알고리즘 시도
            sampleRate.toFloat(),
            audioBufferSize,
            pitchDetectionHandler
        )
        dispatcher?.addAudioProcessor(pitchProcessor)
    }

    private fun handlePitch(result: PitchDetectionResult, audioEvent: AudioEvent) {
        val pitchInHz = result.pitch
        if (pitchInHz != -1f) { // -1은 음고를 감지하지 못한 경우
            textViewPitch.text = kotlin.text.String.format("음고: %.2f Hz", pitchInHz)
            textViewNote.text = kotlin.text.String.format("음이름: %s", pitchToNote(pitchInHz))
        }
    }

    // 주파수를 음이름으로 변환하는 함수 (간단한 버전)
    private fun pitchToNote(frequency: java.lang.Float): kotlin.text.String {
        if (frequency <= 0) return "-" // 유효하지 않은 주파수

        // A4 = 440 Hz 를 기준으로 계산
        // 12 * log2(frequency / 440)
        val midiNote = (12 * log2(frequency / 440f) + 69).roundToInt()
        if (midiNote < 0 || midiNote > 127) return "-" // MIDI 노트 범위 밖

        val noteIndex = midiNote % 12
        val octave = midiNote / 12 - 1 // C4를 4옥타브로 가정
        return NOTES[noteIndex] + octave
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out kotlin.text.String>,
        grantResults: androidx.room.jarjarred.org.antlr.runtime.misc.IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording() // 권한이 허용되면 녹음 시작
            } else {
                textViewPitch.text = "음고: 마이크 권한 필요"
                // 사용자에게 권한이 왜 필요한지 설명하는 UI를 보여주는 것이 좋습니다.
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording() // 액티비티 종료 시 녹음 중지 및 리소스 해제
    }
}
