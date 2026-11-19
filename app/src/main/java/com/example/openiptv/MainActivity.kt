package com.example.openiptv

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var list: ListView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status); list = findViewById(R.id.channelList)
        val input = findViewById<EditText>(R.id.urlInput)
        input.setText(Store.savedUrl(this))
        findViewById<Button>(R.id.loadButton).setOnClickListener { load(input.text.toString().trim()) }
        list.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", pos))
        }
        Store.channels = Store.loadCache(this)
        show()
    }

    private fun show() {
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, Store.channels)
        status.text = if (Store.channels.isEmpty()) "No channels. Enter your own playlist URL." else "${Store.channels.size} channels"
    }

    private fun load(url: String) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) { status.text = "Invalid URL"; return }
        status.text = "Loading…"
        thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 20000
                val text = c.inputStream.bufferedReader().use { it.readText() }
                val ch = M3uParser.parse(text)
                Store.saveUrl(this, url); Store.saveCache(this, text); Store.channels = ch
                runOnUiThread { show() }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Failed: ${e.javaClass.simpleName}" }
            }
        }
    }
}
