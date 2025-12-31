package com.example.yellow

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.abs

class MidiParser {

    data class TempoPoint(val tick: Long, val usPerQuarter: Long, val usAtTick: Long)

    fun parse(input: InputStream): List<MusicalNote> {
        val data = input.readBytes()

        var pos = 0
        fun readU8(): Int = data[pos++].toInt() and 0xFF
        fun readU16BE(): Int {
            val a = readU8()
            val b = readU8()
            return (a shl 8) or b
        }
        fun readU32BE(): Long {
            val a = readU8().toLong()
            val b = readU8().toLong()
            val c = readU8().toLong()
            val d = readU8().toLong()
            return (a shl 24) or (b shl 16) or (c shl 8) or d
        }
        fun readStr(n: Int): String {
            val s = String(data, pos, n, Charsets.US_ASCII)
            pos += n
            return s
        }
        fun readVLQ(): Long {
            var value = 0L
            while (true) {
                val b = readU8()
                value = (value shl 7) or (b and 0x7F).toLong()
                if ((b and 0x80) == 0) break
            }
            return value
        }

        // Header
        val chunkId = readStr(4)
        require(chunkId == "MThd") { "Invalid MIDI header: $chunkId" }
        val headerLen = readU32BE().toInt()
        val headerStart = pos
        val format = readU16BE()
        val nTracks = readU16BE()
        val divisionRaw = readU16BE()
        pos = headerStart + headerLen

        // division 처리
        val isSmpte = (divisionRaw and 0x8000) != 0
        val ppq = if (!isSmpte) divisionRaw else 0
        val smpteFps = if (isSmpte) {
            val fpsByte = (divisionRaw shr 8) and 0xFF
            // two's complement
            val fpsSigned = (fpsByte.toByte()).toInt() // -24, -25, -29, -30
            abs(fpsSigned)
        } else 0
        val smpteTicksPerFrame = if (isSmpte) (divisionRaw and 0xFF) else 0

        val tempoEvents = mutableListOf<Pair<Long, Long>>() // (tick, usPerQuarter)
        tempoEvents.add(0L to 500_000L) // default 120bpm

        data class NoteOn(val startTick: Long, val velocity: Int, val channel: Int)

        // key=(channel<<8)|note
        val openNotes = HashMap<Int, ArrayDeque<NoteOn>>()
        data class RawNote(val note: Int, val startTick: Long, val endTick: Long, val velocity: Int, val channel: Int)
        val rawNotes = mutableListOf<RawNote>()

        for (t in 0 until nTracks) {
            val id = readStr(4)
            require(id == "MTrk") { "Invalid track header: $id" }
            val len = readU32BE().toInt()
            val trackEnd = pos + len

            var tick = 0L
            var runningStatus = 0

            while (pos < trackEnd) {
                val delta = readVLQ()
                tick += delta

                var status = readU8()

                // running status
                if (status < 0x80) {
                    // data byte였는데 status로 읽힘 → runningStatus 사용
                    pos--
                    status = runningStatus
                } else {
                    runningStatus = status
                }

                when {
                    status == 0xFF -> { // Meta
                        val type = readU8()
                        val mlen = readVLQ().toInt()
                        if (type == 0x51 && mlen == 3) {
                            val b1 = readU8()
                            val b2 = readU8()
                            val b3 = readU8()
                            val usPerQuarter = ((b1 shl 16) or (b2 shl 8) or b3).toLong()
                            tempoEvents.add(tick to usPerQuarter)
                        } else {
                            pos += mlen
                        }
                    }
                    status == 0xF0 || status == 0xF7 -> { // SysEx
                        val slen = readVLQ().toInt()
                        pos += slen
                    }
                    else -> {
                        val msgType = status and 0xF0
                        val channel = status and 0x0F

                        fun key(ch: Int, note: Int) = (ch shl 8) or (note and 0xFF)

                        when (msgType) {
                            0x80 -> { // Note Off
                                val note = readU8()
                                val vel = readU8()
                                val k = key(channel, note)
                                val stack = openNotes[k]
                                if (stack != null && stack.isNotEmpty()) {
                                    val on = stack.removeLast()
                                    rawNotes.add(RawNote(note, on.startTick, tick, on.velocity, channel))
                                }
                            }
                            0x90 -> { // Note On (vel=0이면 off)
                                val note = readU8()
                                val vel = readU8()
                                val k = key(channel, note)
                                if (vel == 0) {
                                    val stack = openNotes[k]
                                    if (stack != null && stack.isNotEmpty()) {
                                        val on = stack.removeLast()
                                        rawNotes.add(RawNote(note, on.startTick, tick, on.velocity, channel))
                                    }
                                } else {
                                    val stack = openNotes.getOrPut(k) { ArrayDeque() }
                                    stack.addLast(NoteOn(tick, vel, channel))
                                }
                            }
                            0xA0, 0xB0, 0xE0 -> {
                                // 2 data bytes
                                readU8(); readU8()
                            }
                            0xC0, 0xD0 -> {
                                // 1 data byte
                                readU8()
                            }
                            else -> {
                                // 안전 처리: 알 수 없는 메시지면 트랙 종료 방지용으로 스킵 시도
                                // (일반적으로 여기 오면 데이터가 깨진 것)
                                // 남은 1바이트라도 읽어서 진행
                                if (pos < trackEnd) readU8()
                            }
                        }
                    }
                }
            }

            pos = trackEnd
        }

        // Tempo map → tick->us 변환용 세그먼트 만들기
        tempoEvents.sortBy { it.first }
        val tempoPoints = mutableListOf<TempoPoint>()
        var lastTick = 0L
        var lastUs = 0L
        var lastTempo = tempoEvents.first().second

        tempoPoints.add(TempoPoint(0L, lastTempo, 0L))

        for (i in 1 until tempoEvents.size) {
            val (tick, tempo) = tempoEvents[i]
            if (tick < lastTick) continue

            val usAtThisTick = if (!isSmpte) {
                val usPerTick = lastTempo.toDouble() / ppq.toDouble()
                lastUs + ((tick - lastTick) * usPerTick).toLong()
            } else {
                // SMPTE: tick 기반이 아니라 frame 기반
                val usPerTick = (1_000_000.0 / smpteFps.toDouble()) / smpteTicksPerFrame.toDouble()
                lastUs + ((tick - lastTick) * usPerTick).toLong()
            }

            tempoPoints.add(TempoPoint(tick, tempo, usAtThisTick))
            lastTick = tick
            lastUs = usAtThisTick
            lastTempo = tempo
        }

        fun tickToUs(tick: Long): Long {
            // 가장 마지막 tempo point 찾기 (선형도 충분하지만 binary로)
            var lo = 0
            var hi = tempoPoints.size - 1
            var idx = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (tempoPoints[mid].tick <= tick) {
                    idx = mid
                    lo = mid + 1
                } else hi = mid - 1
            }
            val tp = tempoPoints[idx]
            val dt = tick - tp.tick
            val usPerTick = if (!isSmpte) tp.usPerQuarter.toDouble() / ppq.toDouble()
            else (1_000_000.0 / smpteFps.toDouble()) / smpteTicksPerFrame.toDouble()
            return tp.usAtTick + (dt * usPerTick).toLong()
        }

        // 드럼 채널(10번=9)을 빼면 “음 이상”이 줄어드는 경우가 많음
        val filtered = rawNotes.filter { it.channel != 9 && it.endTick > it.startTick }

        val result = filtered.map { rn ->
            val startUs = tickToUs(rn.startTick)
            val endUs = tickToUs(rn.endTick)
            val startMs = (startUs / 1000L)
            val durMs = ((endUs - startUs) / 1000L).coerceAtLeast(1L)

            MusicalNote(
                note = rn.note,
                startTime = startMs,
                duration = durMs
            )
        }.sortedBy { it.startTime }

        return result
    }
}
