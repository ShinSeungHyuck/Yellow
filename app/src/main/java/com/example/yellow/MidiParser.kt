package com.example.yellow

import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream

class MidiParser {

    fun parse(inputStream: InputStream): List<MusicalNote> {
        try {
            val bis = BufferedInputStream(inputStream)

            // 1. Read Header Chunk
            val headerId = String(readBytes(bis, 4))
            if (headerId != "MThd") {
                Log.e("MidiParser", "Invalid MIDI file: Header not found.")
                return emptyList()
            }
            val headerLength = readInt(bis)
            val format = readShort(bis)
            val trackCount = readShort(bis)
            val division = readShort(bis)

            // 2. Read Track Chunks
            val allNotes = mutableListOf<MusicalNote>()
            for (i in 0 until trackCount) {
                allNotes.addAll(parseTrack(bis))
            }
            return allNotes
        } catch (e: Exception) {
            Log.e("MidiParser", "Failed to parse MIDI file", e)
            return emptyList()
        }
    }

    private fun parseTrack(stream: BufferedInputStream): List<MusicalNote> {
        val trackId = String(readBytes(stream, 4))
        if (trackId != "MTrk") {
            Log.e("MidiParser", "Invalid Track: Header not found.")
            return emptyList()
        }
        val trackLength = readInt(stream)

        val notesOn = mutableMapOf<Int, MusicalNote>()
        val completedNotes = mutableListOf<MusicalNote>()
        var absoluteTick: Long = 0
        var lastStatusByte = 0

        var bytesRead = 0
        while (bytesRead < trackLength) {
            val deltaTime = readVariableLength(stream)
            absoluteTick += deltaTime.value
            bytesRead += deltaTime.bytesRead

            var statusByte = stream.read()
            bytesRead++

            if (statusByte < 0x80) { // Running status
                stream.reset()
                statusByte = lastStatusByte
                bytesRead-- // We didn't consume this byte
            }
            stream.mark(1)

            lastStatusByte = statusByte
            val command = statusByte and 0xF0

            when (command) {
                0x90 -> { // Note On
                    val pitch = stream.read()
                    val velocity = stream.read()
                    bytesRead += 2
                    if (velocity > 0) {
                        notesOn[pitch] = MusicalNote(pitch, absoluteTick, 0)
                    } else { // Note On with velocity 0 is a Note Off
                        notesOn.remove(pitch)?.let {
                            completedNotes.add(it.copy(durationInTicks = absoluteTick - it.tick))
                        }
                    }
                }
                0x80 -> { // Note Off
                    val pitch = stream.read()
                    stream.read() // velocity
                    bytesRead += 2
                    notesOn.remove(pitch)?.let {
                        completedNotes.add(it.copy(durationInTicks = absoluteTick - it.tick))
                    }
                }
                0xF0 -> { // Meta Event or System Exclusive
                    if (statusByte == 0xFF) { // Meta Event
                        stream.read() // type
                        val metaLength = readVariableLength(stream)
                        stream.skip(metaLength.value)
                        bytesRead += 2 + metaLength.bytesRead + metaLength.value
                    } else { // Sysex
                        val sysexLength = readVariableLength(stream)
                        stream.skip(sysexLength.value)
                        bytesRead += 1 + sysexLength.bytesRead + sysexLength.value
                    }
                }
                else -> { // Other channel messages
                    val byteCount = when(command) {
                        0xA0, 0xB0, 0xE0 -> 2
                        0xC0, 0xD0 -> 1
                        else -> 0
                    }
                    stream.skip(byteCount.toLong())
                    bytesRead += byteCount
                }
            }
        }
        return completedNotes
    }

    private fun readBytes(stream: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        if (stream.read(bytes) != count) throw IOException("Unexpected end of stream")
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
