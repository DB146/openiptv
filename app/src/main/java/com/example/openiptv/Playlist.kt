package com.example.openiptv

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale

/** The fixed playlist (BuildConfig.M3U_URL), its on-disk cache, last-played memory and channel lookup. */
object Playlist {
    @Volatile var channels: List<Channel> = emptyList()

    private fun cacheFile(c: Context) = File(c.filesDir, "playlist.m3u")
    private fun prefs(c: Context) = c.getSharedPreferences("state", 0)

    fun loadCache(c: Context) {
        runCatching { channels = M3uParser.parse(cacheFile(c).readText()) }
    }

    fun isFresh(c: Context) = channels.isNotEmpty() &&
        System.currentTimeMillis() - cacheFile(c).lastModified() < 60 * 60 * 1000L

    /** Blocking download; call off the main thread. Returns true if a non-empty playlist was loaded. */
    fun download(c: Context): Boolean {
        if (BuildConfig.M3U_URL.isBlank()) return false
        return try {
            val conn = URL(BuildConfig.M3U_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000; conn.readTimeout = 20000
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val list = M3uParser.parse(text)
            if (list.isEmpty()) return false
            cacheFile(c).writeText(text)
            channels = list
            true
        } catch (e: Exception) { false }
    }

    fun saveLast(c: Context, ch: Channel) =
        prefs(c).edit().putInt("num", ch.number).putString("name", ch.name).apply()

    /** Last played channel (by name, then number), else channel 1. */
    fun last(c: Context): Channel? {
        val p = prefs(c)
        val name = p.getString("name", null)
        return channels.firstOrNull { it.name == name }
            ?: channels.firstOrNull { it.number == p.getInt("num", -1) }
            ?: channels.firstOrNull()
    }

    fun byNumber(n: Int) = channels.firstOrNull { it.number == n }

    /** Digits -> channel number; otherwise accent/case-insensitive name match (exact > prefix > contains). */
    fun find(query: String): Channel? {
        val q = query.trim()
        q.toIntOrNull()?.let { return byNumber(it) }
        val k = norm(q)
        if (k.isEmpty()) return null
        val keyed = channels.map { it to norm(it.name) }
        return keyed.firstOrNull { it.second == k }?.first
            ?: keyed.filter { it.second.startsWith(k) }.minByOrNull { it.second.length }?.first
            ?: keyed.filter { it.second.contains(k) }.minByOrNull { it.second.length }?.first
    }

    /** Lowercase, no accents; "VTV 3 HD" -> "vtv3 hd". */
    private fun norm(s: String): String {
        val t = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace("\\p{M}".toRegex(), "").replace('đ', 'd')
            .replace("[^a-z0-9]+".toRegex(), " ").trim()
        return t.replace("([a-z])\\s+(\\d)".toRegex(), "$1$2")
    }
}
