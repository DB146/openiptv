package com.example.openiptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUrlTest {
    private fun ok(raw: String): ParsedUrl {
        val r = StreamUrl.check(raw)
        assertTrue("expected valid: $raw", r is UrlCheck.Ok)
        return (r as UrlCheck.Ok).parsed
    }

    private fun bad(raw: String): String {
        val r = StreamUrl.check(raw)
        assertTrue("expected invalid: $raw", r is UrlCheck.Bad)
        return (r as UrlCheck.Bad).reason
    }

    @Test fun acceptsHttpAndHttps() {
        assertEquals("https", ok("https://example.com/a.m3u8").scheme)
        val p = ok("http://example.com:8080/live/x")
        assertEquals("http", p.scheme)
        assertEquals("example.com", p.host)
        assertEquals(8080, p.port)
        assertEquals("/live/x", p.path)
    }

    @Test fun schemeIsCaseInsensitive() {
        val p = ok("HTTPS://Example.COM/x")
        assertEquals("https", p.scheme)
    }

    @Test fun acceptsIpv4Ipv6AndInternationalHosts() {
        assertEquals("192.168.1.10", ok("http://192.168.1.10:8000/live/1.ts").host)
        assertEquals("[::1]", ok("http://[::1]:8080/x").host)
        ok("https://tên-miền.vn/x")
        ok("https://cdn_1.example.com/x")
    }

    @Test fun trimsSurroundingWhitespace() {
        assertEquals("https://example.com/x", ok("  https://example.com/x \r").url)
    }

    @Test fun parsesQueryParameters() {
        val p = ok("https://h/p?a=1&A=2&b=%C3%A9&c&mac=00:1A:79:3F:0C:96")
        assertEquals(listOf("1", "2"), p.query["a"])
        assertEquals("1", p.param("A"))
        assertEquals("é", p.param("b"))
        assertEquals("", p.param("c"))
        assertEquals("00:1A:79:3F:0C:96", p.param("mac"))
        assertNull(p.param("missing"))
    }

    @Test fun rejectsEmptyAndMissingScheme() {
        bad("")
        bad("   ")
        bad("example.com/stream")
        bad("//example.com/stream")
    }

    @Test fun rejectsNonHttpSchemes() {
        for (u in listOf("ftp://h/x", "rtmp://h/live", "file:///etc/passwd", "udp://@239.0.0.1:1234", "javascript://x")) {
            assertTrue(u, bad(u).contains("unsupported scheme"))
        }
    }

    @Test fun rejectsMissingOrInvalidHost() {
        bad("http://")
        bad("http:///x")
        bad("http://.example.com/x")
        bad("http://a..b/x")
        bad("http://ho\$t/x")
    }

    @Test fun rejectsBadPorts() {
        bad("http://h:0/x")
        bad("http://h:99999/x")
        bad("http://h:abc/x")
    }

    @Test fun rejectsWhitespaceInsideUrl() {
        assertTrue(bad("https://h/a b").contains("whitespace"))
        // two URLs pasted onto one line
        bad("https://a.example.com/ https://b.example.com/x.m3u8")
        bad("https://h/x\u0000y")
    }

    @Test fun rejectsMalformedPercentEncodingInQuery() {
        bad("https://h/x?q=%zz")
        bad("https://h/x?q=%4")
    }

    @Test fun reasonsNeverEchoTheUrl() {
        for (u in listOf("https://h/x?token=SECRET%zz", "ftp://h/SECRET", "https://h/SECRET y")) {
            assertFalse(u, bad(u).contains("SECRET"))
        }
    }

    private fun type(url: String, manifest: String? = null) = StreamUrl.classify(ok(url), manifest)

    @Test fun classifiesByPathExtension() {
        assertEquals(StreamType.HLS, type("https://h/a/index.m3u8"))
        assertEquals(StreamType.HLS, type("https://h/a/INDEX.M3U8?token=1"))
        assertEquals(StreamType.DASH, type("https://h/a/manifest.mpd"))
        assertEquals(StreamType.TS, type("http://h/a/stream.ts"))
    }

    @Test fun classifiesPhpEndpointsByExtensionParameter() {
        assertEquals(StreamType.TS, type("http://h:80/play/live.php?mac=00:1A:79:3F:0C:96&stream=7&extension=ts&play_token=x"))
        assertEquals(StreamType.TS, type("http://h/play/live.php?EXTENSION=TS"))
        assertEquals(StreamType.HLS, type("http://h/play/live.php?stream=7&extension=m3u8"))
        assertEquals(StreamType.UNKNOWN, type("http://h/play/live.php?stream=7"))
    }

    @Test fun manifestTypeWinsOverUrl() {
        assertEquals(StreamType.DASH, type("https://h/tv/?id=7", "mpd"))
        assertEquals(StreamType.DASH, type("https://h/mpd.php?id=7&cdn=1", "MPD"))
        assertEquals(StreamType.HLS, type("https://h/tv/?id=7", "hls"))
        assertEquals(StreamType.DASH, type("https://h/index.m3u8", "mpd"))
    }

    @Test fun extensionlessUrlsAreUnknown() {
        assertEquals(StreamType.UNKNOWN, type("https://h/tv/?id=tv360-9"))
        assertEquals(StreamType.UNKNOWN, type("https://h/VTVGo/?sctv7"))
        assertEquals(StreamType.UNKNOWN, type("https://h/tv/?id=7", "ism")) // unsupported hint: fall back to the URL
    }
}
