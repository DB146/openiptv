package com.example.openiptv

import android.content.Context
import java.io.File

/** Keeps the playlist URL and a cached copy of the playlist text in app-private storage. */
object Store {
    var channels: List<Channel> = emptyList()

    fun savedUrl(c: Context) = c.getSharedPreferences("prefs", 0).getString("url", "") ?: ""
    fun saveUrl(c: Context, u: String) = c.getSharedPreferences("prefs", 0).edit().putString("url", u).apply()
    private fun cache(c: Context) = File(c.filesDir, "playlist.m3u")
    fun saveCache(c: Context, t: String) = cache(c).writeText(t)
    fun loadCache(c: Context): List<Channel> =
        runCatching { M3uParser.parse(cache(c).readText()) }.getOrDefault(emptyList())
}
