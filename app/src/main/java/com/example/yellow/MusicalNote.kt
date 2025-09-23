package com.example.yellow

data class MusicalNote(
    val note: Int,       // MIDI note number (60 = C4)
    val startTime: Long, // in milliseconds
    val duration: Long   // in milliseconds
)
