package com.example.yellow.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SongCatalogRepository(
    private val baseUrl: String = "https://r2-music-search.den1116.workers.dev"
) {
    data class CatalogItem(
        val title: String,
        val melodyUrl: String,
        val midiUrl: String
    )

    fun fetchCatalog(limit: Int = 500): List<CatalogItem> {
        val url = "${baseUrl.trimEnd('/')}/catalog?limit=$limit"
        val body = httpGetText(url)

        val obj = JSONObject(body)
        val arr = obj.optJSONArray("items") ?: return emptyList()

        val out = ArrayList<CatalogItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title", "")
            val melodyUrl = o.optString("melodyUrl", "")
            val midiUrl = o.optString("midiUrl", "")
            if (title.isNotBlank() && melodyUrl.isNotBlank() && midiUrl.isNotBlank()) {
                out.add(CatalogItem(title, melodyUrl, midiUrl))
            }
        }
        return out
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
            val text = String(bytes)
            if (code !in 200..299) throw RuntimeException("HTTP $code: $text")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
