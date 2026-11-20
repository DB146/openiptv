package com.example.openiptv

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Runs against a throwaway local server, so it needs no network. */
class HttpClientTest {
    private lateinit var server: HttpServer
    private val seen = ConcurrentHashMap<String, Map<String, String>>()
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.start()
    }

    @After fun stop() = server.stop(0)

    private fun route(path: String, status: Int = 200, headers: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0), delayMs: Long = 0) {
        server.createContext(path) { ex: HttpExchange ->
            seen[path] = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            if (delayMs > 0) Thread.sleep(delayMs)
            headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
            ex.close()
        }
    }

    private fun failure(block: () -> Unit): HttpError {
        try {
            block()
        } catch (e: HttpError) {
            return e
        }
        fail("expected HttpError")
        throw AssertionError()
    }

    @Test fun readsABody() {
        route("/ok", body = "hello".toByteArray())
        val r = HttpClient.fetch("$base/ok")
        assertEquals(200, r.status)
        assertEquals("hello", String(r.body))
        assertEquals(0, r.redirects)
        assertFalse(r.truncated)
    }

    @Test fun followsRelativeAndAbsoluteRedirects() {
        route("/a", 302, mapOf("Location" to "/b"))
        route("/b", 301, mapOf("Location" to "$base/c"))
        route("/c", body = "done".toByteArray())
        val r = HttpClient.fetch("$base/a")
        assertEquals("done", String(r.body))
        assertEquals("$base/c", r.finalUrl)
        assertEquals(2, r.redirects)
    }

    @Test fun followsTemporaryAndPermanentRedirectCodes() {
        for (code in listOf(301, 302, 303, 307, 308)) {
            route("/r$code", code, mapOf("Location" to "/end"))
        }
        route("/end", body = "x".toByteArray())
        for (code in listOf(301, 302, 303, 307, 308)) {
            assertEquals("code $code", "x", String(HttpClient.fetch("$base/r$code").body))
        }
    }

    @Test fun sendsChannelHeadersOnEveryHop() {
        route("/start", 302, mapOf("Location" to "/final"))
        route("/final", body = "x".toByteArray())
        HttpClient.fetch("$base/start", mapOf("User-Agent" to "Dalvik/2.1.0", "Referer" to "https://angiangtv.example/"))
        for (path in listOf("/start", "/final")) {
            assertEquals(path, "Dalvik/2.1.0", seen[path]!!["user-agent"])
            assertEquals(path, "https://angiangtv.example/", seen[path]!!["referer"])
        }
    }

    @Test fun asksForUncompressedContent() {
        route("/x", body = "x".toByteArray())
        HttpClient.fetch("$base/x")
        assertEquals("identity", seen["/x"]!!["accept-encoding"])
    }

    @Test fun sniffModeSendsARangeHeader() {
        route("/x", body = "x".toByteArray())
        HttpClient.fetch("$base/x", maxBytes = 1024, sniff = true)
        assertEquals("bytes=0-1023", seen["/x"]!!["range"])
        HttpClient.fetch("$base/x")
        assertFalse(seen["/x"]!!.containsKey("range"))
    }

    @Test fun truncatesLargeBodies() {
        route("/big", body = ByteArray(5000) { 7 })
        val r = HttpClient.fetch("$base/big", maxBytes = 1024)
        assertEquals(1024, r.body.size)
        assertTrue(r.truncated)
    }

    @Test fun bodyExactlyAtTheLimitIsNotTruncated() {
        route("/exact", body = ByteArray(1024) { 7 })
        val r = HttpClient.fetch("$base/exact", maxBytes = 1024)
        assertEquals(1024, r.body.size)
        assertFalse(r.truncated)
    }

    @Test fun detectsRedirectLoops() {
        route("/l1", 302, mapOf("Location" to "/l2"))
        route("/l2", 302, mapOf("Location" to "/l1"))
        val e = failure { HttpClient.fetch("$base/l1") }
        assertEquals(HttpError.Kind.REDIRECT, e.kind)
        assertTrue(e.message!!.contains("loop"))
    }

    @Test fun stopsAfterTooManyRedirects() {
        server.createContext("/hop") { ex ->
            val n = ex.requestURI.query?.removePrefix("n=")?.toIntOrNull() ?: 0
            ex.responseHeaders.add("Location", "/hop?n=${n + 1}")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        val e = failure { HttpClient.fetch("$base/hop") }
        assertEquals(HttpError.Kind.REDIRECT, e.kind)
        assertTrue(e.message!!.contains("${HttpClient.MAX_REDIRECTS}"))
    }

    @Test fun blocksRedirectsToOtherSchemes() {
        route("/ftp", 302, mapOf("Location" to "ftp://files.example.com/stream"))
        route("/file", 302, mapOf("Location" to "file:///etc/passwd"))
        route("/rtmp", 302, mapOf("Location" to "rtmp://live.example.com/app"))
        for (p in listOf("/ftp", "/file", "/rtmp")) {
            assertEquals(p, HttpError.Kind.REDIRECT, failure { HttpClient.fetch("$base$p") }.kind)
        }
    }

    @Test fun redirectWithoutLocationFails() {
        route("/nolocation", 302)
        assertEquals(HttpError.Kind.REDIRECT, failure { HttpClient.fetch("$base/nolocation") }.kind)
    }

    @Test fun rejectsInvalidFirstUrlWithoutConnecting() {
        for (u in listOf("file:///etc/passwd", "ftp://h/x", "http://", "https://h/a b", "not a url")) {
            assertEquals(u, HttpError.Kind.INVALID_URL, failure { HttpClient.fetch(u) }.kind)
        }
    }

    @Test fun reportsHttpStatusErrors() {
        route("/missing", 404, body = "no".toByteArray())
        route("/denied", 403)
        route("/boom", 500)
        for ((p, code) in listOf("/missing" to 404, "/denied" to 403, "/boom" to 500)) {
            val e = failure { HttpClient.fetch("$base$p") }
            assertEquals(HttpError.Kind.STATUS, e.kind)
            assertTrue(e.message!!.contains("$code"))
        }
    }

    @Test fun reportsTimeouts() {
        route("/slow", body = "x".toByteArray(), delayMs = 1500)
        val e = failure { HttpClient.fetch("$base/slow", readTimeoutMs = 300) }
        assertEquals(HttpError.Kind.TIMEOUT, e.kind)
    }

    @Test fun reportsConnectionFailures() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort } // free, then closed
        val e = failure { HttpClient.fetch("http://127.0.0.1:$port/x", connectTimeoutMs = 1000) }
        assertEquals(HttpError.Kind.IO, e.kind)
    }

    @Test fun errorMessagesNeverContainTheQueryString() {
        route("/secret", 404)
        val e = failure { HttpClient.fetch("$base/secret?play_token=TOPSECRET") }
        assertFalse(e.message!!.contains("TOPSECRET"))
    }
}
