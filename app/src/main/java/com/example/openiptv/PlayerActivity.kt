package com.example.openiptv

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var title: TextView
    private lateinit var overlay: ListView
    private var index = 0
    private val channels get() = Store.channels

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_player)
        title = findViewById(R.id.title); overlay = findViewById(R.id.overlayList)
        overlay.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, channels)
        overlay.setOnItemClickListener { _, _, pos, _ -> overlay.visibility = View.GONE; play(pos) }
        index = intent.getIntExtra("index", 0)
        if (channels.isEmpty()) finish()
    }

    override fun onStart() {
        super.onStart()
        if (channels.isEmpty()) return
        play(index)
    }

    private fun play(i: Int) {
        if (channels.isEmpty()) return
        index = (i + channels.size) % channels.size
        val ch = channels[index]
        title.text = ch.name
        player?.release()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ch.userAgent ?: "Mozilla/5.0")
            .setAllowCrossProtocolRedirects(true)
        ch.referer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Playback error: ${e.errorCodeName}", Toast.LENGTH_SHORT).show()
            }
        })
        findViewById<PlayerView>(R.id.playerView).player = p
        p.setMediaItem(MediaItem.fromUri(ch.url)); p.prepare(); p.playWhenReady = true
        player = p
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && overlay.visibility != View.VISIBLE) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { play(index - 1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { play(index + 1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    overlay.visibility = View.VISIBLE; overlay.setSelection(index); overlay.requestFocus(); return true
                }
            }
        }
        if (e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_BACK && overlay.visibility == View.VISIBLE) {
            overlay.visibility = View.GONE; return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onStop() { super.onStop(); player?.release(); player = null }
}
