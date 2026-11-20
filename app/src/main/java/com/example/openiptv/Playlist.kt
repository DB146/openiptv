package com.example.openiptv

import android.content.Context
import android.util.Log
import java.io.File
import java.text.Normalizer
import java.util.Locale

/** The fixed playlist (BuildConfig.M3U_URL), its on-disk cache, last-played memory and channel lookup. */
object Playlist {
    private const val TAG = "OpenIPTV"
    private const val MAX_PLAYLIST_BYTES = 16 * 1024 * 1024

    @Volatile var channels: List<Channel> = emptyList()
    /** XMLTV guide URLs from the #EXTM3U header (url-tvg), validated. */
    @Volatile var epgUrls: List<String> = emptyList()
    /** Entries skipped or flagged during the last parse; also written to logcat (tag OpenIPTV). */
    @Volatile var issues: List<ParseIssue> = emptyList()

    private fun cacheFile(c: Context) = File(c.filesDir, "playlist.m3u")
    private fun prefs(c: Context) = c.getSharedPreferences("state", 0)

    private fun apply(doc: ParsedPlaylist) {
        doc.issues.forEach { Log.w(TAG, "playlist line ${it.line}: ${it.message}") }
        channels = doc.channels
        epgUrls = doc.header.epgUrls
        issues = doc.issues
    }

    fun loadCache(c: Context) {
        runCatching { apply(M3uParser.parseDocument(cacheFile(c).readText())) }
    }

    fun isFresh(c: Context) = channels.isNotEmpty() &&
        System.currentTimeMillis() - cacheFile(c).lastModified() < 60 * 60 * 1000L

    /**
     * Blocking download; call off the main thread. Returns true if a non-empty playlist was loaded.
     * The URL must be http:// or https://; redirects (including http<->https) are followed and
     * TLS certificates are verified. On failure the cached playlist stays in use.
     */
    fun download(c: Context): Boolean {
        if (BuildConfig.M3U_URL.isBlank()) return false
        return try {
            val r = HttpClient.fetch(BuildConfig.M3U_URL, maxBytes = MAX_PLAYLIST_BYTES, readTimeoutMs = 20_000)
            if (r.truncated) {
                Log.w(TAG, "Playlist larger than $MAX_PLAYLIST_BYTES bytes, ignored")
                return false
            }
            val text = r.body.toString(Charsets.UTF_8)
            val doc = M3uParser.parseDocument(text)
            if (doc.channels.isEmpty()) return false
            cacheFile(c).writeText(text)
            apply(doc)
            true
        } catch (e: HttpError) {
            Log.w(TAG, "Playlist download failed (${e.kind}): ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Playlist download failed: ${e.javaClass.simpleName}")
            false
        }
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
