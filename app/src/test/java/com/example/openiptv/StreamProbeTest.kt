package com.example.openiptv

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class StreamProbeTest {
    private fun ts(size: Int, sync: List<Int> = listOf(0, 188, 376)) =
        ByteArray(size).also { b -> sync.filter { it < size }.forEach { b[it] = 0x47 } }

    private fun classify(ct: String?, body: ByteArray) = StreamProbe.classify(ct, body)
    private fun classify(ct: String?, body: String) = StreamProbe.classify(ct, body.toByteArray(Charsets.ISO_8859_1))

    // ---- classify (pure) -------------------------------------------------------

    @Test fun contentTypeDecidesFirst() {
        assertEquals(StreamType.HLS, classify("application/vnd.apple.mpegurl; charset=utf-8", ""))
        assertEquals(StreamType.HLS, classify("application/x-mpegURL", ""))
        assertEquals(StreamType.HLS, classify("audio/mpegurl", ""))
        assertEquals(StreamType.DASH, classify("application/dash+xml", ""))
        assertEquals(StreamType.TS, classify("video/MP2T", ""))
    }

    @Test fun sniffsHlsPlaylists() {
        assertEquals(StreamType.HLS, classify("application/octet-stream", "#EXTM3U\n#EXT-X-VERSION:3\n"))
        assertEquals(StreamType.HLS, classify(null, "\n  #EXTM3U\n"))
        assertEquals(StreamType.HLS, classify("text/plain", "ï»¿#EXTM3U\n")) // UTF-8 BOM
    }

    @Test fun sniffsDashManifests() {
        assertEquals(StreamType.DASH, classify("text/xml", """<?xml version="1.0"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011">"""))
        assertEquals(StreamType.DASH, classify(null, "<?xml version=\"1.0\"?>\n<!-- generated -->\n<MPD type=\"dynamic\">"))
        assertEquals(StreamType.DASH, classify(null, "<MPD>"))
    }

    @Test fun sniffsTransportStreams() {
        assertEquals(StreamType.TS, classify("application/octet-stream", ts(1024)))
        assertEquals(StreamType.TS, classify(null, ts(200, listOf(0, 188)))) // short sniff, two packets seen
        assertEquals(StreamType.TS, classify(null, ts(1024, listOf(37, 225, 413)))) // live stream joined mid-packet
    }

    @Test fun doesNotMistakeOtherContentForAStream() {
        assertEquals(StreamType.UNKNOWN, classify("text/html", "<html><body>Forbidden</body></html>"))
        assertEquals(StreamType.UNKNOWN, classify("text/xml", """<?xml version="1.0"?><rss version="2.0">"""))
        assertEquals(StreamType.UNKNOWN, classify(null, ts(1024, listOf(0)))) // 0x47 once is not enough
        assertEquals(StreamType.UNKNOWN, classify(null, ts(100)))
        assertEquals(StreamType.UNKNOWN, classify(null, ts(1024, listOf(0, 188)).also { it[376] = 1 }))
        assertEquals(StreamType.UNKNOWN, classify(null, ts(1024, listOf(37))))
        assertEquals(StreamType.UNKNOWN, classify(null, ByteArray(0)))
        assertEquals(StreamType.UNKNOWN, classify(null, "plain text"))
    }

    // ---- detect (against a local server) ---------------------------------------

    private lateinit var server: HttpServer
    private val seen = ConcurrentHashMap<String, Map<String, String>>()
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.start()
    }

    @After fun stop() = server.stop(0)

    private fun route(path: String, status: Int = 200, ct: String? = null, location: String? = null, body: ByteArray = ByteArray(0)) {
        server.createContext(path) { ex ->
            seen[path] = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            ct?.let { ex.responseHeaders.add("Content-Type", it) }
            location?.let { ex.responseHeaders.add("Location", it) }
            ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
            ex.close()
        }
    }

    @Test fun detectsHlsDashAndTsFromAnExtensionlessUrl() {
        route("/hls", ct = "application/octet-stream", body = "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray())
        route("/dash", ct = "text/plain", body = """<?xml version="1.0"?><MPD type="dynamic"></MPD>""".toByteArray())
        route("/ts", ct = "application/octet-stream", body = ts(2000))
        assertEquals(StreamType.HLS, StreamProbe.detect("$base/hls?id=1", emptyMap()).type)
        assertEquals(StreamType.DASH, StreamProbe.detect("$base/dash?id=1", emptyMap()).type)
        assertEquals(StreamType.TS, StreamProbe.detect("$base/ts?id=1", emptyMap()).type)
    }

    @Test fun followsRedirectsToTheRealStream() {
        route("/go", 302, location = "/hls")
        route("/hls", body = "#EXTM3U\n".toByteArray())
        assertEquals(StreamType.HLS, StreamProbe.detect("$base/go", emptyMap()).type)
    }

    @Test fun sendsTheChannelsOwnHeaders() {
        route("/x", body = "#EXTM3U\n".toByteArray())
        StreamProbe.detect("$base/x", mapOf("User-Agent" to "VThanhTivi", "Referer" to "https://site.example/"))
        assertEquals("VThanhTivi", seen["/x"]!!["user-agent"])
        assertEquals("https://site.example/", seen["/x"]!!["referer"])
    }

    @Test fun failuresComeBackAsUnknownWithTheError() {
        route("/gone", 404)
        val p = StreamProbe.detect("$base/gone", emptyMap())
        assertEquals(StreamType.UNKNOWN, p.type)
        assertEquals(HttpError.Kind.STATUS, p.error!!.kind)
    }

    @Test fun unrecognizedContentIsUnknownWithoutAnError() {
        route("/page", ct = "text/html", body = "<html></html>".toByteArray())
        val p = StreamProbe.detect("$base/page", emptyMap(), retryDelayMs = 0)
        assertEquals(StreamType.UNKNOWN, p.type)
        assertNull(p.error)
    }

    @Test fun invalidUrlIsReportedNotThrown() {
        val p = StreamProbe.detect("rtmp://h/live", emptyMap())
        assertEquals(StreamType.UNKNOWN, p.type)
        assertEquals(HttpError.Kind.INVALID_URL, p.error!!.kind)
    }

    @Test fun retriesWhenTheRedirectingEndpointAnswersBadlyAtFirst() {
        val hits = java.util.concurrent.atomic.AtomicInteger()
        server.createContext("/play") { ex ->
            if (hits.incrementAndGet() < 3) {
                ex.responseHeaders.add("Content-Type", "text/html")
                val b = "<html>try later</html>".toByteArray()
                ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            } else {
                ex.responseHeaders.add("Location", "/hls"); ex.sendResponseHeaders(302, -1)
            }
            ex.close()
        }
        route("/hls", body = "#EXTM3U\n".toByteArray())
        val p = StreamProbe.detect("$base/play?sctv9", emptyMap(), retryDelayMs = 0)
        assertEquals(StreamType.HLS, p.type)
        assertEquals(3, hits.get())
    }

    @Test fun retriesTransientServerErrorsButNotClientErrors() {
        val hits = java.util.concurrent.atomic.AtomicInteger()
        server.createContext("/flaky") { ex ->
            if (hits.incrementAndGet() == 1) ex.sendResponseHeaders(503, -1)
            else { val b = "#EXTM3U\n".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
            ex.close()
        }
        assertEquals(StreamType.HLS, StreamProbe.detect("$base/flaky", emptyMap(), retryDelayMs = 0).type)
        route("/forbidden", 403)
        assertEquals(HttpError.Kind.STATUS, StreamProbe.detect("$base/forbidden", emptyMap(), retryDelayMs = 0).error!!.kind)
    }

    @Test fun fallsBackToTheFinalUrlsExtensionWhenTheBodyIsNoHelp() {
        route("/go", 302, location = "/live/index.m3u8?token=abc")
        route("/live/index.m3u8", ct = "application/octet-stream") // empty body
        val p = StreamProbe.detect("$base/go", emptyMap(), retryDelayMs = 0)
        assertEquals(StreamType.HLS, p.type)
        assertEquals("$base/live/index.m3u8?token=abc", p.finalUrl)
    }
}
