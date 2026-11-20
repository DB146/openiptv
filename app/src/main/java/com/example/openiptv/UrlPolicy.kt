package com.example.openiptv

/**
 * http/https policy for stream URLs (BuildConfig.HTTP_MODE, see [HttpMode]).
 * FORCE rewrites every https:// to http://. That turns TLS off, so tokens, headers and the stream are readable on the
 * network, and https-only hosts fail or redirect back to https. FALLBACK only downgrades after https has failed, but
 * anyone who can block port 443 can trigger that, so it is not a security boundary either.
 */
/** How https stream URLs are treated. */
enum class HttpMode {
    /** Use URLs exactly as written. */
    STRICT,
    /** Try https; if the secure connection itself fails (TLS error, refused, timeout), retry the same URL over http. */
    FALLBACK,
    /** Rewrite every https:// to http:// up front. */
    FORCE;

    companion object {
        fun parse(s: String?): HttpMode = when (s?.trim()?.lowercase()) {
            "strict", "off", "false" -> STRICT
            "force", "http", "true" -> FORCE
            else -> FALLBACK
        }
    }
}

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

    fun apply(url: String, forceHttp: Boolean) = if (forceHttp) downgrade(url) else url
}
