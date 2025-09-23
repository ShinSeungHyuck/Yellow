package com.example.yellow

import javax.sound.midi.MidiSystem
import javax.sound.midi.ShortMessage
import java.io.InputStream

class MidiParser {

    fun parse(inputStream: InputStream): List<MusicalNote> {
        val sequence = MidiSystem.getSequence(inputStream)
        val notes = mutableListOf<MusicalNote>()

        for (track in sequence.tracks) {
            for (i in 0 until track.size()) {
                val event = track.get(i)
                val message = event.message
                if (message is ShortMessage && message.command == ShortMessage.NOTE_ON && message.data2 > 0) {
                    val pitch = message.data1
                    val tick = event.tick

                    // Find the corresponding NOTE_OFF event
                    var durationInTicks: Long = 0
                    for (j in i + 1 until track.size()) {
                        val nextEvent = track.get(j)
                        val nextMessage = nextEvent.message
                        if (nextMessage is ShortMessage && nextMessage.command == ShortMessage.NOTE_OFF && nextMessage.data1 == pitch) {
                            durationInTicks = nextEvent.tick - tick
                            break
                        }
                    }

                    if (durationInTicks > 0) {
                        notes.add(MusicalNote(pitch, tick, durationInTicks))
                    }
                }
            }
        }
        return notes
    }
}
