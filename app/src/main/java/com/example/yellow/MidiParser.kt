package com.example.yellow

import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

class MidiParser {

    fun parse(inputStream: InputStream): List<MusicalNote> {
        try {
            // Use a BufferedInputStream for mark/reset support
            val bis = if (inputStream.markSupported()) inputStream else BufferedInputStream(inputStream)
            Log.d("MidiParser", "Parsing MIDI stream...")

            // 1. Read Header Chunk
            val headerId = String(readBytes(bis, 4))
            Log.d("MidiParser", "Header ID: $headerId")
            if (headerId != "MThd") {
                Log.e("MidiParser", "Invalid MIDI file: Header ID is not MThd.")
                return emptyList()
            }
            val headerLength = readInt(bis)
            val format = readShort(bis)
            val trackCount = readShort(bis)
            val division = readShort(bis) // Ticks per quarter note
            Log.d("MidiParser", "Header - Format: $format, Tracks: $trackCount, Division: $division")


            // 2. Read all tracks
            val allNotes = mutableListOf<MusicalNote>()
            for (i in 0 until trackCount) {
                allNotes.addAll(parseTrack(bis, division))
            }
            Log.d("MidiParser", "Total notes parsed: ${allNotes.size}")
            return allNotes
        } catch (e: Exception) {
            Log.e("MidiParser", "Failed to parse MIDI file", e)
            return emptyList()
        }
    }

    private fun parseTrack(stream: InputStream, division: Int): List<MusicalNote> {
        val trackId = String(readBytes(stream, 4))
        if (trackId != "MTrk") {
            Log.w("MidiParser", "Invalid Track header found: $trackId. Skipping.")
            return emptyList()
        }
        val trackLength = readInt(stream)
        val trackBytes = readBytes(stream, trackLength)
        val trackStream = BufferedInputStream(trackBytes.inputStream())

        val notesOn = mutableMapOf<Int, MusicalNote>()
        val completedNotes = mutableListOf<MusicalNote>()
        var absoluteTick: Long = 0
        var lastStatusByte = 0
        var tempo = 500000 // Default tempo: 120 bpm (500,000 microseconds per quarter note)

        while (trackStream.available() > 0) {
            val deltaTime = readVariableLength(trackStream)
            absoluteTick += deltaTime.value

            trackStream.mark(1)
            var statusByte = trackStream.read()

            if (statusByte < 0x80) { // Running status
                trackStream.reset()
                statusByte = lastStatusByte
            } else {
                lastStatusByte = statusByte
            }

            val command = statusByte and 0xF0

            when (command) {
                0x90 -> { // Note On
                    val pitch = trackStream.read()
                    val velocity = trackStream.read()
                    if (velocity > 0) {
                        val startTimeMs = ticksToMs(absoluteTick, tempo, division)
                        notesOn[pitch] = MusicalNote(pitch, startTimeMs, 0)
                    } else { // Note On with velocity 0 is actually Note Off
                        notesOn.remove(pitch)?.let {
                            val startTimeMs = it.startTime
                            val endTimeMs = ticksToMs(absoluteTick, tempo, division)
                            completedNotes.add(it.copy(duration = endTimeMs - startTimeMs))
                        }
                    }
                }
                0x80 -> { // Note Off
                    val pitch = trackStream.read()
                    trackStream.read() // velocity
                    notesOn.remove(pitch)?.let {
                        val startTimeMs = it.startTime
                        val endTimeMs = ticksToMs(absoluteTick, tempo, division)
                        completedNotes.add(it.copy(duration = endTimeMs - startTimeMs))
                    }
                }
                 0xF0 -> { // Meta Event or System Exclusive
                    if (statusByte == 0xFF) { // Meta Event
                        val metaType = trackStream.read()
                        val metaLength = readVariableLength(trackStream).value
                        if (metaType == 0x51) { // Set Tempo
                             if (metaLength == 3L) {
                                val b1 = trackStream.read()
                                val b2 = trackStream.read()
                                val b3 = trackStream.read()
                                tempo = (b1 shl 16) or (b2 shl 8) or b3
                             } else {
                                trackStream.skip(metaLength)
                             }
                        } else {
                            trackStream.skip(metaLength)
                        }
                    } else { // Sysex
                        val sysexLength = readVariableLength(trackStream).value
                        trackStream.skip(sysexLength)
                    }
                }
                else -> { // Other channel messages (Polyphonic Pressure, Control Change, etc.)
                    val byteCount = when(command) {
                        0xA0, 0xB0, 0xE0 -> 2 // 2 data bytes
                        0xC0, 0xD0 -> 1 // 1 data byte
                        else -> 0
                    }
                    trackStream.skip(byteCount.toLong())
                }
            }
        }
        return completedNotes
    }

    private fun ticksToMs(ticks: Long, tempo: Int, division: Int): Long {
        // tempo is microseconds per quarter note
        // division is ticks per quarter note
        val msPerTick = tempo.toDouble() / division.toDouble() / 1000.0
        return (ticks * msPerTick).toLong()
    }

    private fun readBytes(stream: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while(offset < count) {
            val read = stream.read(bytes, offset, count - offset)
            if (read == -1) throw IOException("Unexpected end of stream. Expected $count bytes, got $offset")
            offset += read
        }
        return bytes
    }

    private fun readInt(stream: InputStream): Int {
        val bytes = readBytes(stream, 4)
        return (bytes[0].toInt() and 0xFF shl 24) or
               (bytes[1].toInt() and 0xFF shl 16) or
               (bytes[2].toInt() and 0xFF shl 8) or
               (bytes[3].toInt() and 0xFF)
    }

    private fun readShort(stream: InputStream): Int {
        val bytes = readBytes(stream, 2)
        return (bytes[0].toInt() and 0xFF shl 8) or (bytes[1].toInt() and 0xFF)
    }

    private data class VLV(val value: Long, val bytesRead: Int)

    private fun readVariableLength(stream: InputStream): VLV {
        var value: Long = 0
        var byte: Int
        var bytesRead = 0
        do {
            byte = stream.read()
            if (byte == -1) throw IOException("Unexpected end of stream in VLV")
            bytesRead++
            value = (value shl 7) or (byte and 0x7F).toLong()
        } while (byte and 0x80 != 0)
        return VLV(value, bytesRead)
    }
}