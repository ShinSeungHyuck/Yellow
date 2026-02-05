package com.example.yellow.data

import java.security.MessageDigest

data class Song(
    val id: String,
    val title: String,
    val melodyUrl: String,
    val midiUrl: String,
    val queryTitle: String = title
) {
    companion object {
        fun makeId(melodyUrl: String, midiUrl: String): String {
            val raw = "$melodyUrl|$midiUrl"
            val bytes = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
