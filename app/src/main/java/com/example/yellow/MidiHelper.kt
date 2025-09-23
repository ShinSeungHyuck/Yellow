package com.example.yellow

import android.content.Context
import android.util.Log
import com.leff.midi.MidiFile
import com.leff.midi.event.NoteOn
import com.leff.midi.event.meta.Tempo

class MidiHelper(private val context: Context) {

    fun getMidiNotes(fileName: String): List<Pair<Float, Int>> {
        val notes = mutableListOf<Pair<Float, Int>>()
        try {
            val midiFile = MidiFile(context.assets.open(fileName))

            // Find the track with the most notes, likely the melody track
            val track = midiFile.tracks.maxByOrNull { track -> track.events.count { it is NoteOn } } ?: midiFile.tracks[0]

            var currentTime = 0f
            var currentTempo = 500000 // Default tempo (120 BPM)

            val iterator = track.events.iterator()
            while (iterator.hasNext()) {
                val event = iterator.next()

                if (event is Tempo) {
                    currentTempo = event.microsecondsPerQuarterNote
                }

                // Convert delta time in ticks to seconds
                currentTime += event.deltaTime * currentTempo / (midiFile.resolution * 1000000f)

                if (event is NoteOn && event.velocity > 0) {
                    notes.add(Pair(currentTime, event.noteValue))
                }
            }
        } catch (e: Exception) {
            Log.e("MidiHelper", "Error parsing MIDI file: ", e)
        }
        return notes
    }
}
