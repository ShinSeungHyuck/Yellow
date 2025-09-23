package com.example.yellow

import android.content.Context
import com.leff.midi.MidiFile
import com.leff.midi.event.NoteOn
import java.io.InputStream

class MidiParser {

    fun parse(inputStream: InputStream): List<MusicalNote> {
        val midiFile = MidiFile(inputStream)
        val notes = mutableListOf<MusicalNote>()

        for (track in midiFile.tracks) {
            for (event in track.events) {
                if (event is NoteOn && event.velocity > 0) {
                    val note = MusicalNote(
                        event.noteValue,
                        event.tick,
                        event.durationInTicks
                    )
                    notes.add(note)
                }
            }
        }

        return notes
    }
}
