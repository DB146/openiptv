package com.example.openiptv

/** Parses standard #EXTM3U playlists, incl. group-title, tvg-logo and VLC-style UA/referrer options. */
object M3uParser {
    private fun attr(line: String, key: String): String? =
        Regex("(?:^|\\s)" + Regex.escape(key) + "=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(line)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null; var group: String? = null; var logo: String? = null
        var ua: String? = null; var ref: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val low = line.lowercase()
            when {
                low.startsWith("#extinf") -> {
                    name = line.substringAfterLast(',', line).trim()
                    group = attr(line, "group-title"); logo = attr(line, "tvg-logo")
                }
                low.startsWith("#extvlcopt:http-user-agent=") -> ua = line.substringAfter('=').trim()
                low.startsWith("#extvlcopt:http-referrer=") -> ref = line.substringAfter('=').trim()
                line.startsWith("#") -> {}
                else -> {
                    if ((low.startsWith("http://") || low.startsWith("https://")) && name != null) {
                        out.add(Channel(name, line, group, logo, ua, ref))
                    }
                    name = null; group = null; logo = null; ua = null; ref = null
                }
            }
        }
        return out
    }
}
