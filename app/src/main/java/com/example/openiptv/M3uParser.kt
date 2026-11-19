package com.example.openiptv

/** Parses #EXTM3U playlists (incl. VLC-style user-agent / referrer options) and numbers channels from 1. */
object M3uParser {
    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var ua: String? = null
        var ref: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val low = line.lowercase()
            when {
                low.startsWith("#extinf") -> name = line.substringAfterLast(',', line).trim()
                low.startsWith("#extvlcopt:http-user-agent=") -> ua = line.substringAfter('=').trim()
                low.startsWith("#extvlcopt:http-referrer=") -> ref = line.substringAfter('=').trim()
                line.startsWith("#") -> {}
                else -> {
                    if (name != null && (low.startsWith("http://") || low.startsWith("https://"))) {
                        out.add(Channel(out.size + 1, name, line, ua, ref))
                    }
                    name = null; ua = null; ref = null
                }
            }
        }
        return out
    }
}
