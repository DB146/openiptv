package com.example.openiptv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlin.concurrent.thread

/**
 * The only screen. Always plays a channel:
 *  - launched normally            -> last channel (or #1)
 *  - action com.example.openiptv.PLAY, extra "number" (int) or "name"/"query" (string)
 *  - deep link openiptv://channel/<number|name>
 * Remote: Up/Down or CH+/CH- change channel, digits type a number, Left (or OK) opens the channel grid.
 */
class PlayerActivity : Activity() {
    private companion object { const val COLUMNS = 3 }
    private var player: ExoPlayer? = null
    private var current: Channel? = null
    private var request: Intent? = null
    private var started = false
    private var typed = ""
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var surface: SurfaceView
    private lateinit var title: TextView
    private lateinit var entry: TextView
    private lateinit var overlay: GridView

    private val hideTitle = Runnable { title.visibility = View.GONE }
    private val commitTyped = Runnable { commitNumber() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_player)
        surface = findViewById(R.id.surface); title = findViewById(R.id.title)
        entry = findViewById(R.id.entry); overlay = findViewById(R.id.overlayList)
        // Channel menu: left half of the screen, 3 channels per row (1 2 3 / 4 5 6 / ...).
        overlay.layoutParams = (overlay.layoutParams as FrameLayout.LayoutParams).apply {
            width = resources.displayMetrics.widthPixels / 2
        }
        overlay.setOnItemClickListener { _, _, pos, _ ->
            closeMenu()
            Playlist.channels.getOrNull(pos)?.let { play(it) }
        }
        request = intent
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (Playlist.channels.isEmpty()) Playlist.loadCache(this)
        if (Playlist.channels.isNotEmpty()) {
            fillList(); applyRequest()
            if (!Playlist.isFresh(this)) refresh()
        } else {
            showTitle("Loading playlist…")
            refresh()
        }
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        request = i
        if (started && Playlist.channels.isNotEmpty()) applyRequest()
    }

    private fun refresh() {
        thread {
            val ok = Playlist.download(this)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (ok) { fillList(); if (current == null && started) applyRequest() }
                else if (Playlist.channels.isEmpty()) {
                    showTitle(if (BuildConfig.M3U_URL.isBlank()) "No playlist URL set in this build" else "Cannot load playlist")
                }
            }
        }
    }

    private fun fillList() {
        overlay.adapter = ArrayAdapter(this, R.layout.item_channel, R.id.text, Playlist.channels)
    }

    private fun openMenu() {
        overlay.visibility = View.VISIBLE
        overlay.requestFocus()
        overlay.setSelection(Playlist.channels.indexOf(current).coerceAtLeast(0))
    }

    private fun closeMenu() { overlay.visibility = View.GONE }

    // ---- choosing what to play -------------------------------------------------

    private fun applyRequest() {
        val i = request; request = null
        val wanted = parse(i)
        val ch = if (wanted != null) Playlist.find(wanted) else null
        if (wanted != null && ch == null) Toast.makeText(this, "Channel not found: $wanted", Toast.LENGTH_SHORT).show()
        (ch ?: current ?: Playlist.last(this))?.let { play(it) }
    }

    /** Returns the requested number/name as a string, or null if the intent doesn't ask for a channel. */
    private fun parse(i: Intent?): String? {
        if (i == null) return null
        i.data?.takeIf { it.scheme == "openiptv" }?.let { d ->
            val v = d.lastPathSegment ?: d.getQueryParameter("q")
            if (!v.isNullOrBlank()) return v
        }
        val ex = i.extras ?: return null
        for (k in arrayOf("number", "name", "query", "channel")) {
            ex.get(k)?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    // ---- playback --------------------------------------------------------------

    private fun play(ch: Channel) {
        if (ch == current && player != null) { showChannel(ch); return }
        current = ch
        Playlist.saveLast(this, ch)
        player?.release(); player = null
        showChannel(ch)
        if (ch.streamType != StreamType.UNKNOWN) { startPlayback(ch, ch.streamType); return }
        // No extension, no manifest_type: ask the server what this URL is before handing it to ExoPlayer,
        // which would otherwise treat it as a plain file. Skipped if the user has already moved on.
        thread {
            val probe = StreamProbe.detect(ch.url, ch.requestHeaders)
            runOnUiThread {
                if (isDestroyed || !started || current !== ch) return@runOnUiThread
                val err = probe.error
                if (err != null && err.kind == HttpError.Kind.TLS) {
                    // Deterministic and security relevant: say so instead of a generic playback error.
                    Toast.makeText(this, "${ch.name}: ${err.message}", Toast.LENGTH_LONG).show()
                } else {
                    startPlayback(ch, probe.type)
                }
            }
        }
    }

    private fun startPlayback(ch: Channel, type: StreamType) {
        player?.release(); player = null
        // User-Agent / Referer from #EXTVLCOPT go on every request: manifest and media segments alike.
        val headers = ch.requestHeaders
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(headers["User-Agent"])
            .setDefaultRequestProperties(headers.filterKeys { it != "User-Agent" })
            .setAllowCrossProtocolRedirects(true) // http<->https hops; TLS certificates are still verified
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val msf = DefaultMediaSourceFactory(http)
        var clearKey = false
        val keySpec = ch.licenseKey
        if (ch.usesClearKey && keySpec != null) {
            val drm = ClearKey.sessionManager(keySpec)
            if (drm != null) { msf.setDrmSessionManagerProvider { drm }; clearKey = true }
            else Toast.makeText(this, "Invalid ClearKey for ${ch.name}", Toast.LENGTH_SHORT).show()
        } else if (ch.licenseType != null && ch.licenseType != "clearkey") {
            Toast.makeText(this, "Unsupported DRM '${ch.licenseType}' for ${ch.name}", Toast.LENGTH_SHORT).show()
        }
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(msf).build()
        p.setVideoSurfaceView(surface)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Playback error: ${e.errorCodeName}", Toast.LENGTH_SHORT).show()
            }
        })
        val item = MediaItem.Builder().setUri(ch.url).apply {
            // The MIME type tells ExoPlayer how to read URLs it can't infer from the extension (.php, ?id=...).
            mimeTypeFor(type)?.let { setMimeType(it) }
            if (clearKey) setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).build())
        }.build()
        p.setMediaItem(item); p.prepare(); p.playWhenReady = true
        player = p
    }

    private fun mimeTypeFor(type: StreamType): String? = when (type) {
        StreamType.HLS -> MimeTypes.APPLICATION_M3U8
        StreamType.DASH -> MimeTypes.APPLICATION_MPD
        StreamType.TS -> MimeTypes.VIDEO_MP2T
        StreamType.UNKNOWN -> null // let ExoPlayer sniff the container
    }

    private fun step(delta: Int) {
        val list = Playlist.channels
        if (list.isEmpty()) return
        val idx = list.indexOf(current).let { if (it < 0) 0 else it }
        play(list[(idx + delta + list.size) % list.size])
    }

    private fun showChannel(ch: Channel) = showTitle("${ch.number}  ${ch.name}")

    private fun showTitle(s: String) {
        title.text = s; title.visibility = View.VISIBLE
        ui.removeCallbacks(hideTitle); ui.postDelayed(hideTitle, 3000)
    }

    // ---- remote control --------------------------------------------------------

    private fun commitNumber() {
        ui.removeCallbacks(commitTyped)
        val n = typed.toIntOrNull(); typed = ""; entry.visibility = View.GONE
        if (n != null) Playlist.byNumber(n)?.let { play(it) }
            ?: Toast.makeText(this, "No channel $n", Toast.LENGTH_SHORT).show()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            val k = e.keyCode
            if (overlay.visibility == View.VISIBLE) {
                // Back, or Right from the last column, closes the menu.
                if (k == KeyEvent.KEYCODE_BACK ||
                    (k == KeyEvent.KEYCODE_DPAD_RIGHT && overlay.selectedItemPosition % COLUMNS == COLUMNS - 1)) {
                    closeMenu(); return true
                }
                return super.dispatchKeyEvent(e)
            }
            when {
                k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 || k in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                    val d = if (k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) k - KeyEvent.KEYCODE_0 else k - KeyEvent.KEYCODE_NUMPAD_0
                    if (typed.length < 4) typed += d
                    entry.text = typed; entry.visibility = View.VISIBLE
                    ui.removeCallbacks(commitTyped); ui.postDelayed(commitTyped, 1500)
                    return true
                }
                typed.isNotEmpty() && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) -> { commitNumber(); return true }
                k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_CHANNEL_UP -> { step(-1); return true }
                k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_CHANNEL_DOWN -> { step(1); return true }
                k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER -> {
                    openMenu(); return true
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onStop() {
        started = false
        player?.release(); player = null
        super.onStop()
    }
}
