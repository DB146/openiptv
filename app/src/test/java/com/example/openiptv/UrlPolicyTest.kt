package com.example.openiptv

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class UrlPolicyTest {
    @Test fun downgradesHttps() {
        assertEquals("http://h.example.com/a.m3u8?t=1#f", UrlPolicy.downgrade("https://h.example.com/a.m3u8?t=1#f"))
        assertEquals("http://h.example.com/x", UrlPolicy.downgrade("HTTPS://h.example.com/x"))
        assertEquals("http://h.example.com", UrlPolicy.downgrade("https://h.example.com"))
        assertEquals("http://h.example.com?id=1", UrlPolicy.downgrade("https://h.example.com?id=1"))
    }

    @Test fun dropsDefaultHttpsPortOnly() {
        assertEquals("http://h/x", UrlPolicy.downgrade("https://h:443/x"))
        assertEquals("http://h:8443/x", UrlPolicy.downgrade("https://h:8443/x"))
        assertEquals("http://[::1]/x", UrlPolicy.downgrade("https://[::1]:443/x"))
        assertEquals("http://h/path:443", UrlPolicy.downgrade("https://h/path:443"))
    }

    @Test fun leavesOtherUrlsAlone() {
        assertEquals("http://h/x", UrlPolicy.downgrade("http://h/x"))
        assertEquals("ftp://h/x", UrlPolicy.downgrade("ftp://h/x"))
    }

    @Test fun applyIsOffUnlessAsked() {
        assertEquals("https://h/x", UrlPolicy.apply("https://h/x", false))
        assertEquals("http://h/x", UrlPolicy.apply("https://h/x", true))
    }

    @Test fun httpClientRewritesFirstUrlAndRedirectTargets() {
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val port = s.address.port
        s.createContext("/start") { ex -> ex.responseHeaders.add("Location", "https://127.0.0.1:$port/end"); ex.sendResponseHeaders(302, -1); ex.close() }
        s.createContext("/end") { ex -> val b = "plain".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }; ex.close() }
        s.start()
        try {
            // an https:// URL pointing at a plain-http server only works if both hops were rewritten
            val r = HttpClient.fetch("https://127.0.0.1:$port/start", forceHttp = true)
            assertEquals("plain", String(r.body))
            assertEquals("http://127.0.0.1:$port/end", r.finalUrl)
        } finally { s.stop(0) }
    }
}

class HttpModeTest {
    @Test fun parsesModesWithFallbackAsTheDefault() {
        assertEquals(HttpMode.FALLBACK, HttpMode.parse(null))
        assertEquals(HttpMode.FALLBACK, HttpMode.parse("fallback"))
        assertEquals(HttpMode.FALLBACK, HttpMode.parse("garbage"))
        assertEquals(HttpMode.STRICT, HttpMode.parse(" Strict "))
        assertEquals(HttpMode.FORCE, HttpMode.parse("FORCE"))
    }
}
