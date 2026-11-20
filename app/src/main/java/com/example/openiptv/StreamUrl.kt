package com.example.openiptv

import java.net.MalformedURLException
import java.net.URL
import java.net.URLDecoder

/** What kind of stream a URL points at. [UNKNOWN] means "ask the server" (see [StreamProbe]). */
enum class StreamType { HLS, DASH, TS, UNKNOWN }

/** A stream URL that passed [StreamUrl.check]. [query] has decoded values; names are lowercased. */
data class ParsedUrl(
    val url: String,
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String,
    val query: Map<String, List<String>>,
) {
    /** First value of query parameter [name] (case-insensitive), or null. */
    fun param(name: String): String? = query[name.lowercase()]?.firstOrNull()
}

sealed class UrlCheck {
    data class Ok(val parsed: ParsedUrl) : UrlCheck()
    /** [reason] never contains the URL itself: stream URLs often carry tokens. */
    data class Bad(val reason: String) : UrlCheck()
}

/** Validation and classification of http:// and https:// stream URLs. */
object StreamUrl {
    fun check(raw: String): UrlCheck {
        val s = raw.trim()
        if (s.isEmpty()) return UrlCheck.Bad("empty URL")
        if (s.any { it.isWhitespace() || it.isISOControl() }) {
            return UrlCheck.Bad("URL contains whitespace or control characters (two URLs on one line?)")
        }
        val sep = s.indexOf("://")
        if (sep < 1) return UrlCheck.Bad("missing scheme, expected http:// or https://")
        val scheme = s.substring(0, sep).lowercase()
        if (scheme != "http" && scheme != "https") {
            return UrlCheck.Bad("unsupported scheme '$scheme', only http and https are supported")
        }
        val url = try {
            URL(s)
        } catch (e: MalformedURLException) {
            return UrlCheck.Bad("malformed URL (${e.message})")
        }
        val host = url.host.orEmpty()
        if (host.isEmpty()) return UrlCheck.Bad("missing host")
        if (!validHost(host)) return UrlCheck.Bad("invalid host name")
        if (url.port != -1 && url.port !in 1..65535) return UrlCheck.Bad("port out of range")
        val query = parseQuery(url.query) ?: return UrlCheck.Bad("malformed percent-encoding in query string")
        return UrlCheck.Ok(ParsedUrl(s, scheme, host, url.port, url.path.orEmpty(), query))
    }

    /**
     * Decides the stream type without touching the network. Order: explicit manifest_type (KODIPROP),
     * then the path extension, then an `extension=` query parameter (live.php?...&extension=ts).
     */
    fun classify(url: ParsedUrl, manifestType: String? = null): StreamType {
        when (manifestType?.trim()?.lowercase()) {
            "mpd", "dash" -> return StreamType.DASH
            "hls", "m3u8" -> return StreamType.HLS
        }
        val path = url.path.lowercase()
        when {
            path.endsWith(".m3u8") -> return StreamType.HLS
            path.endsWith(".mpd") -> return StreamType.DASH
            path.endsWith(".ts") -> return StreamType.TS
        }
        return when (url.param("extension")?.lowercase()) {
            "ts" -> StreamType.TS
            "m3u8" -> StreamType.HLS
            "mpd" -> StreamType.DASH
            else -> StreamType.UNKNOWN
        }
    }

    /** Letters/digits (incl. IDN), '-', '_', '.', and IPv6 literals; no empty labels. */
    private fun validHost(host: String): Boolean {
        if (host.startsWith("[")) return host.endsWith("]") && host.length > 2
        if (host.startsWith(".") || host.contains("..")) return false
        return host.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    }

    /** Returns null if the query has a malformed %-escape. */
    private fun parseQuery(q: String?): Map<String, List<String>>? {
        if (q.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, MutableList<String>>()
        for (part in q.split('&')) {
            if (part.isEmpty()) continue
            try {
                val name = URLDecoder.decode(part.substringBefore('='), "UTF-8").lowercase()
                val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                out.getOrPut(name) { ArrayList() }.add(value)
            } catch (e: IllegalArgumentException) {
                return null
            }
        }
        return out
    }
}
