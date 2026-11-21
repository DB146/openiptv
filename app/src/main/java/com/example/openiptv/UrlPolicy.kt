package com.example.openiptv

/** https -> http helper used when a secure connection to a host fails (see [FallbackDataSource]). */
object UrlPolicy {
    /** https://host:443/x?y -> http://host/x?y. Other schemes and non-default ports are kept as they are. */
    fun downgrade(url: String): String {
        if (!url.startsWith("https://", ignoreCase = true)) return url
        val rest = url.substring("https://".length)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        var authority = rest.substring(0, end)
        // :443 is https's default port; on http it would mean something else, so drop it (http then uses 80)
        if (authority.endsWith(":443")) authority = authority.removeSuffix(":443")
        return "http://" + authority + rest.substring(end)
    }
}
