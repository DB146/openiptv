package com.example.openiptv

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The local server speaks plain http only, so an https:// URL to it fails the TLS handshake:
 * a faithful stand-in for "this host's https is broken".
 */
class HttpFallbackTest {
    private lateinit var server: HttpServer
    private val port get() = server.address.port
    private val hits = java.util.concurrent.atomic.AtomicInteger()

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/ok") { ex -> hits.incrementAndGet(); val b = "plain".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }; ex.close() }
        server.createContext("/missing") { ex -> hits.incrementAndGet(); ex.sendResponseHeaders(404, -1); ex.close() }
        server.start()
    }

    @After fun stop() = server.stop(0)

    private fun kind(block: () -> Unit): HttpError.Kind {
        try { block() } catch (e: HttpError) { return e.kind }
        fail("expected HttpError"); throw AssertionError()
    }

    @Test fun withoutFallbackABrokenHttpsHostFails() {
        assertEquals(HttpError.Kind.TLS, kind { HttpClient.fetch("https://127.0.0.1:$port/ok") })
        assertEquals(0, hits.get())
    }

    @Test fun withFallbackTheSameUrlIsRetriedOverHttp() {
        val r = HttpClient.fetch("https://127.0.0.1:$port/ok", fallbackToHttp = true)
        assertEquals("plain", String(r.body))
        assertEquals("http://127.0.0.1:$port/ok", r.finalUrl)
    }

    @Test fun aStatusErrorIsReportedAndNotRetried() {
        // Only the kinds TLS, IO and TIMEOUT retry. (This uses a plain-http URL; the https-with-status case is not covered here.)
        val e = try { HttpClient.fetch("http://127.0.0.1:$port/missing", fallbackToHttp = true); null } catch (e: HttpError) { e }
        assertEquals(HttpError.Kind.STATUS, e!!.kind)
        assertEquals("a status error must not be retried", 1, hits.get())
    }

    @Test fun plainHttpUrlsAreNeverRetried() {
        val dead = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertEquals(HttpError.Kind.IO, kind { HttpClient.fetch("http://127.0.0.1:$dead/x", fallbackToHttp = true, connectTimeoutMs = 500) })
    }

    @Test fun ifHttpAlsoFailsTheErrorIsReported() {
        val dead = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val k = kind { HttpClient.fetch("https://127.0.0.1:$dead/x", fallbackToHttp = true, connectTimeoutMs = 500) }
        assertTrue(k == HttpError.Kind.IO || k == HttpError.Kind.TIMEOUT)
    }

    @Test fun probeUsesTheFallbackToo() {
        server.createContext("/m") { ex -> val b = "#EXTM3U\n".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }; ex.close() }
        assertEquals(StreamType.UNKNOWN, StreamProbe.detect("https://127.0.0.1:$port/m", emptyMap()).type)
        assertEquals(StreamType.HLS, StreamProbe.detect("https://127.0.0.1:$port/m", emptyMap(), fallbackToHttp = true).type)
    }
}
