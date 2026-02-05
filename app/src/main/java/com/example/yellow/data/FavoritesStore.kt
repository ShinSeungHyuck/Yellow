package com.example.yellow.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object FavoritesStore {
    private const val PREF = "yellow_prefs"
    private const val KEY = "favorites_json"

    fun getAll(context: Context): List<Song> {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = ArrayList<Song>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val title = o.optString("title")
            val melodyUrl = o.optString("melodyUrl")
            val midiUrl = o.optString("midiUrl")
            val queryTitle = o.optString("queryTitle", title)
            if (title.isNotBlank() && melodyUrl.isNotBlank() && midiUrl.isNotBlank()) {
                out.add(Song(id, title, melodyUrl, midiUrl, queryTitle))
            }
        }
        return out
    }

    fun isFavorite(context: Context, songId: String): Boolean {
        return getAll(context).any { it.id == songId }
    }

    fun toggle(context: Context, song: Song): Boolean {
        val list = getAll(context).toMutableList()
        val idx = list.indexOfFirst { it.id == song.id }
        val nowFav = if (idx >= 0) {
            list.removeAt(idx)
            false
        } else {
            list.add(0, song)
            true
        }
        save(context, list)
        return nowFav
    }

    fun remove(context: Context, songId: String) {
        val list = getAll(context).filterNot { it.id == songId }
        save(context, list)
    }

    private fun save(context: Context, list: List<Song>) {
        val arr = JSONArray()
        list.forEach { s ->
            val o = JSONObject()
            o.put("id", s.id)
            o.put("title", s.title)
            o.put("melodyUrl", s.melodyUrl)
            o.put("midiUrl", s.midiUrl)
            o.put("queryTitle", s.queryTitle)
            arr.put(o)
        }
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY, arr.toString()).apply()
    }
}
