package com.example.yellow.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.max

class SongSearchRepository {

    data class Match(val key: String, val url: String)
    data class Candidate(val song: Song, val score: Double)

    suspend fun search(query: String): List<Candidate> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val enc = URLEncoder.encode(q, "UTF-8")

        val melodyJson = httpGetText("https://r2-music-search.den1116.workers.dev/search?bucket=melody&q=$enc")
        val midiJson = httpGetText("https://r2-music-search.den1116.workers.dev/search?bucket=midi&q=$enc")

        val melodies = parseMatches(melodyJson)
        val midis = parseMatches(midiJson)

        if (melodies.isEmpty() || midis.isEmpty()) return emptyList()

        val qNorm = normalize(q)

        val out = ArrayList<Candidate>()
        for (m in melodies.take(50)) {
            val mTitle = titleFromKey(m.key)
            val mNorm = normalize(mTitle)

            var best: Match? = null
            var bestSim = -1.0
            for (x in midis.take(80)) {
                val xTitle = titleFromKey(x.key)
                val sim = similarity(mNorm, normalize(xTitle))
                if (sim > bestSim) {
                    bestSim = sim
                    best = x
                }
            }
            val midi = best ?: continue

            val querySim = similarity(qNorm, mNorm)
            val finalScore = 0.75 * querySim + 0.25 * bestSim

            val id = Song.makeId(m.url, midi.url)
            val song = Song(
                id = id,
                title = mTitle,
                melodyUrl = m.url,
                midiUrl = midi.url,
                queryTitle = q
            )
            out.add(Candidate(song, finalScore))
        }

        return out
            .distinctBy { it.song.id }
            .sortedByDescending { it.score }
            .take(30)
    }

    private fun parseMatches(json: String): List<Match> {
        val obj = JSONObject(json)
        val arr = obj.optJSONArray("matches") ?: return emptyList()
        val out = ArrayList<Match>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url", "")
            val key = o.optString("key", "")
            if (url.isNotBlank() && key.isNotBlank()) out.add(Match(key, url))
        }
        return out
    }

    private fun titleFromKey(key: String): String {
        val last = key.substringAfterLast('/')
        return last.substringBeforeLast('.').ifBlank { key }
    }

    private fun httpGetText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream.use { it.readBytes() }
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            return String(bytes)
        } finally {
            conn.disconnect()
        }
    }

    private fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[^0-9a-zA-Z가-힣]"), "")

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dist = levenshtein(a, b)
        val maxLen = max(a.length, b.length).toDouble()
        return 1.0 - (dist / maxLen)
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
