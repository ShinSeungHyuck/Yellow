package com.example.yellow.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object FavoritesStore {
    private const val PREF = "yellow_prefs"
    private const val KEY = "favorites_json"

    data class FavoriteEntry(
        val song: Song,
        val keyOffset: Int = 0 // ✅ 저장할 설정(확장 가능)
    )

    fun getAllEntries(context: Context): List<FavoriteEntry> {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)

        val out = ArrayList<FavoriteEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val title = o.optString("title")
            val melodyUrl = o.optString("melodyUrl")
            val midiUrl = o.optString("midiUrl")
            val queryTitle = o.optString("queryTitle", title)
            val keyOffset = o.optInt("keyOffset", 0) // ✅ 없으면 0

            if (title.isNotBlank() && melodyUrl.isNotBlank() && midiUrl.isNotBlank()) {
                out.add(
                    FavoriteEntry(
                        song = Song(id, title, melodyUrl, midiUrl, queryTitle),
                        keyOffset = keyOffset
                    )
                )
            }
        }
        return out
    }

    fun getAll(context: Context): List<Song> = getAllEntries(context).map { it.song }

    fun isFavorite(context: Context, songId: String): Boolean {
        return getAllEntries(context).any { it.song.id == songId }
    }

    fun getKeyOffset(context: Context, songId: String): Int {
        return getAllEntries(context).firstOrNull { it.song.id == songId }?.keyOffset ?: 0
    }

    /**
     * ✅ 연습 중 key 변경이 끝났을 때(슬라이더 떼는 순간) 저장
     */
    fun setKeyOffset(context: Context, songId: String, keyOffset: Int) {
        val list = getAllEntries(context).toMutableList()
        val idx = list.indexOfFirst { it.song.id == songId }
        if (idx < 0) return // 즐겨찾기 아니면 저장 안 함

        list[idx] = list[idx].copy(keyOffset = keyOffset)
        saveEntries(context, list)
    }

    /**
     * ✅ 즐겨찾기 토글
     * - 추가 시 keyOffsetWhenAdd(현재 키) 같이 저장 가능
     */
    fun toggle(context: Context, song: Song, keyOffsetWhenAdd: Int? = null): Boolean {
        val list = getAllEntries(context).toMutableList()
        val idx = list.indexOfFirst { it.song.id == song.id }

        val nowFav = if (idx >= 0) {
            list.removeAt(idx)
            false
        } else {
            val key = keyOffsetWhenAdd ?: 0
            list.add(0, FavoriteEntry(song = song, keyOffset = key))
            true
        }

        saveEntries(context, list)
        return nowFav
    }

    fun remove(context: Context, songId: String) {
        val list = getAllEntries(context).filterNot { it.song.id == songId }
        saveEntries(context, list)
    }

    private fun saveEntries(context: Context, list: List<FavoriteEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            val s = e.song
            val o = JSONObject()
            o.put("id", s.id)
            o.put("title", s.title)
            o.put("melodyUrl", s.melodyUrl)
            o.put("midiUrl", s.midiUrl)
            o.put("queryTitle", s.queryTitle)
            o.put("keyOffset", e.keyOffset) // ✅ 저장
            arr.put(o)
        }
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY, arr.toString()).apply()
    }
}