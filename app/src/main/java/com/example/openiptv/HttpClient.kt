package com.example.openiptv

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.SSLException

/** A failed request. [kind] lets callers tell a bad certificate from a timeout. Messages carry the host, never the full URL. */
class HttpError(val kind: Kind, message: String, cause: Throwable? = null, val status: Int = 0) : IOException(message, cause) {
    enum class Kind { INVALID_URL, REDIRECT, TLS, TIMEOUT, STATUS, IO }
}

class Fetched(
    val finalUrl: String,
    val status: Int,
    val contentType: String?,
    val body: ByteArray,
    /** True if the response had more than the requested maximum number of bytes. */
    val truncated: Boolean,
    val redirects: Int,
)

/**
 * Small blocking HTTP(S) client on HttpURLConnection. Call off the main thread.
 *
 *  - Only http:// and https:// are allowed, checked for the first URL and for every redirect hop.
 *  - Redirects are followed manually (up to [MAX_REDIRECTS], loops detected), including http<->https
 *    hops, which HttpURLConnection refuses to follow by itself. Relative Location headers are resolved.
 *  - TLS uses the platform defaults: the certificate chain and host name are always verified, and
 *    nothing here installs a trust-all manager. A failure surfaces as [HttpError.Kind.TLS].
 */
object HttpClient {
    const val MAX_REDIRECTS = 10
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    /**
     * GETs [url] and reads at most [maxBytes] of the body. With [sniff] the request carries a
     * `Range` header so servers that honor it send only the first bytes.
     */
    fun fetch(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = 16 * 1024 * 1024,
        sniff: Boolean = false,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000,
        /** If the https:// request fails to connect (TLS error, I/O error, timeout), retry once over http://. HTTP status errors never trigger this. */
        fallbackToHttp: Boolean = false,
    ): Fetched {
        try {
            return fetchOnce(url, headers, maxBytes, sniff, connectTimeoutMs, readTimeoutMs, false)
        } catch (e: HttpError) {
            val retry = fallbackToHttp && url.trim().startsWith("https://", ignoreCase = true) &&
                e.kind in setOf(HttpError.Kind.TLS, HttpError.Kind.IO, HttpError.Kind.TIMEOUT)
            if (!retry) throw e
            return fetchOnce(url, headers, maxBytes, sniff, connectTimeoutMs, readTimeoutMs, true)
        }
    }

    private fun fetchOnce(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = 16 * 1024 * 1024,
        sniff: Boolean = false,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000,
        /** Rewrite every https:// URL, including redirect targets, to http:// (used for the fallback retry). */
        forceHttp: Boolean = false,
    ): Fetched {
        var current = url
        var hops = 0
        val seen = HashSet<String>()
        while (true) {
            val parsed = when (val check = StreamUrl.check(if (forceHttp) UrlPolicy.downgrade(current) else current)) {
                is UrlCheck.Ok -> check.parsed
                is UrlCheck.Bad -> throw HttpError(
                    if (hops == 0) HttpError.Kind.INVALID_URL else HttpError.Kind.REDIRECT,
                    if (hops == 0) "Invalid URL: ${check.reason}" else "Redirect blocked: ${check.reason}",
                )
            }
            if (!seen.add(parsed.url)) throw HttpError(HttpError.Kind.REDIRECT, "Redirect loop at ${parsed.host}")

            val conn = URL(parsed.url).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.setRequestProperty("Accept-Encoding", "identity") // raw bytes, so sniffing sees the real content
                if (sniff) conn.setRequestProperty("Range", "bytes=0-${maxBytes - 1}")
                for ((k, v) in headers) conn.setRequestProperty(k, v)

                val code = conn.responseCode
                if (code in REDIRECT_CODES) {
                    val location = conn.getHeaderField("Location")
                        ?: throw HttpError(HttpError.Kind.REDIRECT, "HTTP $code from ${parsed.host} without Location")
                    if (++hops > MAX_REDIRECTS) {
                        throw HttpError(HttpError.Kind.REDIRECT, "More than $MAX_REDIRECTS redirects from ${parsed.host}")
                    }
                    current = resolve(parsed.url, location)
                    continue
                }
                if (code !in 200..299) throw HttpError(HttpError.Kind.STATUS, "HTTP $code from ${parsed.host}", status = code)

                val (body, truncated) = readBody(conn, maxBytes)
                return Fetched(parsed.url, code, conn.contentType, body, truncated, hops)
            } catch (e: HttpError) {
                throw e
            } catch (e: SSLException) {
                throw HttpError(HttpError.Kind.TLS, "TLS failure for ${parsed.host}: ${e.message}", e)
            } catch (e: SocketTimeoutException) {
                throw HttpError(HttpError.Kind.TIMEOUT, "Timed out talking to ${parsed.host}", e)
            } catch (e: IOException) {
                throw HttpError(HttpError.Kind.IO, "${e.javaClass.simpleName} talking to ${parsed.host}: ${e.message}", e)
            } finally {
                // disconnect() (not close()) so an endless live stream isn't drained
                conn.disconnect()
            }
        }
    }

    private fun readBody(conn: HttpURLConnection, maxBytes: Int): Pair<ByteArray, Boolean> {
        val stream = conn.inputStream
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (total <= maxBytes) { // reads one byte past maxBytes to detect truncation
            val n = stream.read(chunk, 0, minOf(chunk.size, maxBytes + 1 - total))
            if (n < 0) break
            out.write(chunk, 0, n)
            total += n
        }
        val bytes = out.toByteArray()
        return if (bytes.size > maxBytes) bytes.copyOf(maxBytes) to true else bytes to false
    }

    private fun resolve(base: String, location: String): String = try {
        URL(URL(base), location.trim()).toString()
    } catch (e: MalformedURLException) {
        throw HttpError(HttpError.Kind.REDIRECT, "Redirect to an unusable target", e)
    }
}
