package com.example.openiptv

import java.text.Normalizer
import java.util.Locale

/** #EXTM3U header: [attributes] as written (lowercased names) and the validated EPG URLs from url-tvg. */
data class PlaylistHeader(
    val attributes: Map<String, String> = emptyMap(),
    val epgUrls: List<String> = emptyList(),
)

/** A problem found while parsing. [line] is 1-based. Messages never contain stream URLs or keys. */
data class ParseIssue(val line: Int, val message: String)

data class ParsedPlaylist(
    val header: PlaylistHeader,
    val channels: List<Channel>,
    val issues: List<ParseIssue>,
)

/**
 * Parses #EXTM3U playlists and numbers channels from 1.
 *
 * Understood per entry:
 *  - #EXTINF attributes: tvg-id, group-title, tvg-logo, catchup, catchup-days, catchup-source (others kept)
 *  - #EXTVLCOPT: http-user-agent, http-referrer (also http-referer / http-user-referer spellings)
 *  - #KODIPROP: inputstream.adaptive.manifest_type / license_type / license_key
 *  - http:// and https:// stream URLs (validated; entries with bad URLs are skipped and reported)
 *  - entries with the same name are merged into one channel; the extra URLs are fallback sources (see [Channel.fallbacks])
 *
 * #EXTVLCOPT and #KODIPROP lines may come before or after the #EXTINF line of their entry.
 */
object M3uParser {
    private val ATTR = Regex("""([A-Za-z0-9_.:-]+)=(?:"([^"]*)"|([^\s"]*))""")
    private val REFERER_KEYS = listOf("http-referrer", "http-referer", "http-user-referrer", "http-user-referer")
    private val KNOWN_MANIFESTS = setOf("mpd", "dash", "hls", "m3u8")
    private const val MANIFEST_TYPE = "inputstream.adaptive.manifest_type"
    private const val LICENSE_TYPE = "inputstream.adaptive.license_type"
    private const val LICENSE_KEY = "inputstream.adaptive.license_key"

    fun parse(text: String): List<Channel> = parseDocument(text).channels

    fun parseDocument(text: String): ParsedPlaylist {
        var header = PlaylistHeader()
        val channels = ArrayList<Channel>()
        val issues = ArrayList<ParseIssue>()
        val vlc = LinkedHashMap<String, String>()
        val kodi = LinkedHashMap<String, String>()
        var info: ExtInf? = null
        var infoLine = 0
        var lineNo = 0

        for (rawLine in text.lineSequence()) {
            lineNo++
            val line = rawLine.removePrefix("﻿").trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXTM3U", ignoreCase = true) -> header = parseHeader(line)
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    if (info != null) issues.add(ParseIssue(infoLine, "#EXTINF without a stream URL"))
                    info = parseExtInf(line)
                    infoLine = lineNo
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) ->
                    keyValue(line)?.let { vlc[it.first] = it.second }
                line.startsWith("#KODIPROP:", ignoreCase = true) ->
                    keyValue(line)?.let { kodi[normalizeKodiKey(it.first)] = it.second }
                line.startsWith("#") -> {} // comments and tags we don't use
                else -> {
                    val entry = info
                    if (entry == null) {
                        issues.add(ParseIssue(lineNo, "stream URL without #EXTINF, skipped"))
                    } else {
                        when (val check = StreamUrl.check(line)) {
                            is UrlCheck.Bad -> issues.add(ParseIssue(lineNo, "\"${entry.name}\" skipped: ${check.reason}"))
                            is UrlCheck.Ok -> channels.add(
                                buildChannel(0, entry, check.parsed, vlc, kodi, lineNo, issues)
                            )
                        }
                    }
                    info = null
                    vlc.clear()
                    kodi.clear()
                }
            }
        }
        if (info != null) issues.add(ParseIssue(infoLine, "#EXTINF without a stream URL"))
        return ParsedPlaylist(header, mergeSources(channels), issues)
    }

    /**
     * Entries with the same name (ignoring case, accents and spacing) become ONE channel: the first entry is the
     * primary source, the others (same URL dropped) are its [Channel.fallbacks] in playlist order. Channels are then
     * numbered 1..N in order of first appearance.
     */
    private fun mergeSources(all: List<Channel>): List<Channel> {
        val groups = LinkedHashMap<String, MutableList<Channel>>()
        for (c in all) groups.getOrPut(nameKey(c.name)) { ArrayList() }.add(c)
        var n = 0
        return groups.values.map { g ->
            n++
            val extra = g.drop(1).filter { o -> o.url != g[0].url }.distinctBy { it.url }.map { it.copy(number = n) }
            g[0].copy(number = n, fallbacks = extra)
        }
    }

    internal fun nameKey(name: String): String =
        Normalizer.normalize(name.lowercase(Locale.ROOT).replace("+", "plus"), Normalizer.Form.NFD)
            .replace("\\p{M}".toRegex(), "").replace('đ', 'd')
            .replace("[^a-z0-9]+".toRegex(), "")

    private class ExtInf(val name: String, val attrs: Map<String, String>)

    private fun buildChannel(
        number: Int,
        info: ExtInf,
        url: ParsedUrl,
        vlc: Map<String, String>,
        kodi: Map<String, String>,
        lineNo: Int,
        issues: MutableList<ParseIssue>,
    ): Channel {
        val a = info.attrs
        val name = info.name.ifBlank { a.opt("tvg-name") ?: a.opt("tvg-id") ?: url.host }

        val manifestType = kodi.opt(MANIFEST_TYPE)?.lowercase()
        val licenseType = kodi.opt(LICENSE_TYPE)?.lowercase()
        val licenseKey = kodi.opt(LICENSE_KEY)

        if (manifestType != null && manifestType !in KNOWN_MANIFESTS) {
            issues.add(ParseIssue(lineNo, "\"$name\": unsupported manifest_type '$manifestType', detecting from URL instead"))
        }
        if (licenseType != null && licenseType != "clearkey") {
            issues.add(ParseIssue(lineNo, "\"$name\": license_type '$licenseType' is not supported (only clearkey)"))
        }
        if (licenseKey != null && (licenseType == null || licenseType == "clearkey") && ClearKeySpec.parse(licenseKey) == null) {
            issues.add(ParseIssue(lineNo, "\"$name\": invalid ClearKey license_key (expected kid:key hex pairs or JSON {\"keys\":[...]})"))
        }

        return Channel(
            number = number,
            name = name,
            url = url.url,
            streamType = StreamUrl.classify(url, manifestType),
            tvgId = a.opt("tvg-id"),
            group = a.opt("group-title"),
            logo = a.opt("tvg-logo"),
            catchup = a.opt("catchup"),
            catchupDays = a.opt("catchup-days")?.toIntOrNull()?.takeIf { it >= 0 },
            catchupSource = a.opt("catchup-source"),
            userAgent = vlc.opt("http-user-agent"),
            referer = REFERER_KEYS.firstNotNullOfOrNull { vlc.opt(it) },
            manifestType = manifestType,
            licenseType = licenseType,
            licenseKey = licenseKey,
            attributes = a.toMap(),
            vlcOptions = vlc.toMap(),
        )
    }

    private fun parseHeader(line: String): PlaylistHeader {
        val attrs = attributes(line.drop("#EXTM3U".length))
        val epg = listOf("url-tvg", "x-tvg-url", "tvg-url")
            .mapNotNull { attrs[it] }
            .flatMap { it.split(Regex("[,\\s]+")) }
            .filter { it.isNotEmpty() && StreamUrl.check(it) is UrlCheck.Ok }
            .distinct()
        return PlaylistHeader(attrs, epg)
    }

    /** `#EXTINF:<duration> key="value" key2=value2, Title` -> title + attributes. */
    private fun parseExtInf(line: String): ExtInf {
        val body = line.substringAfter(':', "")
        val comma = titleComma(body)
        val meta = if (comma >= 0) body.substring(0, comma) else body
        val title = if (comma >= 0) body.substring(comma + 1).trim() else ""
        return ExtInf(title, attributes(meta))
    }

    private fun attributes(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (m in ATTR.findAll(s)) {
            out[m.groupValues[1].lowercase()] = (m.groups[2]?.value ?: m.groups[3]?.value ?: "").trim()
        }
        return out
    }

    /** Index of the comma that starts the title: the first one outside double quotes. */
    private fun titleComma(s: String): Int {
        var inQuote = false
        for ((i, c) in s.withIndex()) {
            if (c == '"') inQuote = !inQuote
            else if (c == ',' && !inQuote) return i
        }
        return s.lastIndexOf(',') // unbalanced quote: fall back to the last comma
    }

    /** `#TAG:key=value` -> (lowercased key, trimmed value). Values may themselves contain '='. */
    private fun keyValue(line: String): Pair<String, String>? {
        val body = line.substringAfter(':', "")
        val eq = body.indexOf('=')
        if (eq <= 0) return null
        return body.substring(0, eq).trim().lowercase() to body.substring(eq + 1).trim()
    }

    /** Playlists in the wild contain the typo "inpustream.adaptive.*". */
    private fun normalizeKodiKey(key: String) =
        if (key.startsWith("inpustream.")) "inputstream." + key.removePrefix("inpustream.") else key

    private fun Map<String, String>.opt(key: String): String? = this[key]?.trim()?.takeIf { it.isNotEmpty() }
}
