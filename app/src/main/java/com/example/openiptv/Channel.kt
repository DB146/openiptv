package com.example.openiptv

data class Channel(
    val name: String,
    val url: String,
    val group: String? = null,
    val logo: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
) {
    override fun toString() = if (group.isNullOrBlank()) name else "$name  [$group]"
}
