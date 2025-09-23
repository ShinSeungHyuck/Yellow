package com.example.yellow

import android.content.Context
import com.leff.midi.MidiFile
import com.leff.midi.event.NoteOn
import com.leff.midi.event.meta.Tempo

class MidiHelper(private val context: Context) {

    fun getMidiNotes(fileName: String): List<Pair<Float, Int>> {
        val notes = mutableListOf<Pair<Float, Int>>()
        try {
            val midiFile = MidiFile(context.assets.open(fileName))
            val track = midiFile.tracks[1] // 일반적으로 1번 트랙에 멜로디가 있습니다.

            var currentTime = 0f
            var currentTempo = 500000 // 기본 템포 (120 BPM)

            val iterator = track.events.iterator()
            while (iterator.hasNext()) {
                val event = iterator.next()
                currentTime += event.deltaTime * currentTempo / (midiFile.resolution * 1000000f)

                if (event is Tempo) {
                    currentTempo = event.mpqn
                } else if (event is NoteOn && event.velocity > 0) {
                    notes.add(Pair(currentTime, event.noteValue))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return notes
    }
}
