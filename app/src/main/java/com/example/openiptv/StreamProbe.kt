package com.example.openiptv

/**
 * Outcome of [StreamProbe.detect]. [error] is set when the request failed (type is then UNKNOWN).
 * [finalUrl] is where the redirects ended, when a response was received.
 */
class Probe(val type: StreamType, val error: HttpError? = null, val finalUrl: String? = null)

/**
 * For generic URLs with no extension and no manifest_type (e.g. https://host/tv?id=7): fetch the first
 * bytes and decide HLS / DASH / MPEG-TS from the Content-Type and the content itself.
 */
object StreamProbe {
    private const val SNIFF_BYTES = 1024

    /**
     * Blocking. Sends the channel's own headers (User-Agent, Referer) and validates every redirect hop.
     *
     * Redirecting endpoints (e.g. https://host/play?channel -> a tokenised .m3u8 or a live TS) can answer
     * differently from one request to the next: an error page, an empty body, a half-started stream. Handing
     * ExoPlayer an UNKNOWN type then ends in PARSING_CONTAINER_UNSUPPORTED, so an unrecognised or transiently
     * failed probe is retried up to [attempts] times before giving up. TLS and invalid-URL errors are
     * deterministic and returned at once.
     */
    fun detect(
        url: String,
        headers: Map<String, String>,
        forceHttp: Boolean = false,
        fallbackToHttp: Boolean = false,
        attempts: Int = 3,
        retryDelayMs: Long = 400,
    ): Probe {
        var last = Probe(StreamType.UNKNOWN)
        for (i in 1..attempts.coerceAtLeast(1)) {
            last = detectOnce(url, headers, forceHttp, fallbackToHttp)
            if (last.type != StreamType.UNKNOWN) return last
            val err = last.error
            if (err != null && !isTransient(err)) return last
            if (i < attempts && retryDelayMs > 0) {
                try { Thread.sleep(retryDelayMs) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); return last }
            }
        }
        return last
    }

    private fun isTransient(e: HttpError) = when (e.kind) {
        HttpError.Kind.IO, HttpError.Kind.TIMEOUT, HttpError.Kind.REDIRECT -> true
        HttpError.Kind.STATUS -> e.status >= 500 || e.status == 429
        HttpError.Kind.TLS, HttpError.Kind.INVALID_URL -> false
    }

    private fun detectOnce(url: String, headers: Map<String, String>, forceHttp: Boolean, fallbackToHttp: Boolean): Probe = try {
        val r = HttpClient.fetch(url, headers, SNIFF_BYTES, sniff = true, forceHttp = forceHttp, fallbackToHttp = fallbackToHttp)
        var type = classify(r.contentType, r.body)
        // The body didn't say (empty, odd Content-Type): the URL the redirects ended on may (".../index.m3u8?token=").
        if (type == StreamType.UNKNOWN) {
            (StreamUrl.check(r.finalUrl) as? UrlCheck.Ok)?.let { type = StreamUrl.classify(it.parsed) }
        }
        Probe(type, finalUrl = r.finalUrl)
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

    /**
     * MPEG-TS packets are 188 bytes and each starts with the sync byte 0x47. A live stream joined mid-packet
     * starts at an arbitrary offset, so look for the sync pattern at any alignment within the first packet.
     */
    private fun isTransportStream(b: ByteArray): Boolean {
        for (o in 0 until 188) {
            if (o + 188 >= b.size) return false
            if (b[o] == 0x47.toByte() && b[o + 188] == 0x47.toByte() && (o + 376 >= b.size || b[o + 376] == 0x47.toByte())) return true
        }
        return false
    }
}
