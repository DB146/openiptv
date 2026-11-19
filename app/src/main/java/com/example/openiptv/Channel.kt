package com.example.openiptv

/** [number] is assigned automatically: 1..N in playlist order. */
data class Channel(
    val number: Int,
    val name: String,
    val url: String,
    val userAgent: String? = null,
    val referer: String? = null,
    /** ClearKey spec: "kid_hex:key_hex", several pairs separated by ",". */
    val clearKey: String? = null,
) {
    override fun toString() = "$number   $name"
}
