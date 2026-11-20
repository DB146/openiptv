package com.example.openiptv

import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * HttpMode.FALLBACK for ExoPlayer: opens https:// requests normally, and if the secure connection fails
 * (TLS error, connection refused, timeout) reopens the same request over http://. HTTP status errors (403, 404...)
 * are passed through unchanged, because the server did answer. A host that failed once is sent straight to
 * http for the rest of the playback, so every segment doesn't pay the failed https attempt again.
 */
class FallbackDataSource(
    private val upstream: DataSource.Factory,
    private val downgradedHosts: MutableSet<String>,
) : DataSource {
    private var current: DataSource = upstream.createDataSource()
    private val listeners = ArrayList<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
        current.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val host = dataSpec.uri.host
        if (!"https".equals(dataSpec.uri.scheme, ignoreCase = true)) return current.open(dataSpec)
        if (host != null && host in downgradedHosts) return openOverHttp(dataSpec)
        try {
            return current.open(dataSpec)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            throw e // the server answered; http would not help
        } catch (e: IOException) {
            if (host != null) downgradedHosts.add(host)
            Log.w("OpenIPTV", "https failed for $host (${e.javaClass.simpleName}), falling back to http")
            runCatching { current.close() }
            return openOverHttp(dataSpec)
        }
    }

    private fun openOverHttp(spec: DataSpec): Long {
        current = upstream.createDataSource().also { ds -> listeners.forEach { ds.addTransferListener(it) } }
        return current.open(spec.withUri(Uri.parse(UrlPolicy.downgrade(spec.uri.toString()))))
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current.read(buffer, offset, length)

    override fun getUri(): Uri? = current.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current.responseHeaders

    override fun close() = current.close()
}
