package com.example.openiptv

/** Outcome of [StreamProbe.detect]. [error] is set when the request failed (type is then UNKNOWN). */
class Probe(val type: StreamType, val error: HttpError? = null)

/**
 * For generic URLs with no extension and no manifest_type (e.g. https://host/tv?id=7): fetch the first
 * bytes and decide HLS / DASH / MPEG-TS from the Content-Type and the content itself.
 */
object StreamProbe {
    private const val SNIFF_BYTES = 1024

    /** Blocking. Sends the channel's own headers (User-Agent, Referer) and validates every redirect hop. */
    fun detect(url: String, headers: Map<String, String>, forceHttp: Boolean = false, fallbackToHttp: Boolean = false): Probe = try {
        val r = HttpClient.fetch(url, headers, SNIFF_BYTES, sniff = true, forceHttp = forceHttp, fallbackToHttp = fallbackToHttp)
        Probe(classify(r.contentType, r.body))
    } catch (e: HttpError) {
        Probe(StreamType.UNKNOWN, e)
    }

    /** Pure decision from the Content-Type header and the first bytes of the body. */
    fun classify(contentType: String?, head: ByteArray): StreamType {
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        when {
            "mpegurl" in ct -> return StreamType.HLS
            "dash+xml" in ct -> return StreamType.DASH
            ct == "video/mp2t" -> return StreamType.TS
        }

        var start = 0
        if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) start = 3
        while (start < head.size && (head[start].toInt() and 0xFF).toChar().isWhitespace()) start++
        val text = String(head, start, head.size - start, Charsets.ISO_8859_1)
        return when {
            text.startsWith("#EXTM3U") -> StreamType.HLS
            text.startsWith("<") && text.contains("<MPD", ignoreCase = true) -> StreamType.DASH
            isTransportStream(head) -> StreamType.TS
            else -> StreamType.UNKNOWN
        }
    }

    /** MPEG-TS packets are 188 bytes and each starts with the sync byte 0x47. */
    private fun isTransportStream(b: ByteArray): Boolean {
        if (b.size < 189 || b[0] != 0x47.toByte() || b[188] != 0x47.toByte()) return false
        return b.size < 377 || b[376] == 0x47.toByte()
    }
}
