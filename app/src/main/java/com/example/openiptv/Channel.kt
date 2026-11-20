package com.example.openiptv

/** [number] is assigned automatically: 1..N in playlist order. */
data class Channel(
    val number: Int,
    val name: String,
    val url: String,
    val streamType: StreamType = StreamType.UNKNOWN,
    // #EXTINF attributes
    val tvgId: String? = null,
    val group: String? = null,
    val logo: String? = null,
    val catchup: String? = null,
    val catchupDays: Int? = null,
    val catchupSource: String? = null,
    // #EXTVLCOPT http-user-agent / http-referrer (and spelling variants)
    val userAgent: String? = null,
    val referer: String? = null,
    // #KODIPROP inputstream.adaptive.* (values lowercased, except licenseKey which is kept verbatim)
    val manifestType: String? = null,
    val licenseType: String? = null,
    /** ClearKey: "kid_hex:key_hex" pairs separated by ",", or a JSON {"keys":[...]} object. */
    val licenseKey: String? = null,
    /** Every #EXTINF attribute as written (lowercased names), including ones not modelled above. */
    val attributes: Map<String, String> = emptyMap(),
    /** Every #EXTVLCOPT as written (lowercased names). */
    val vlcOptions: Map<String, String> = emptyMap(),
) {
    /** Headers to send with every request for this channel's manifest and media segments. */
    val requestHeaders: Map<String, String>
        get() {
            val h = LinkedHashMap<String, String>()
            h["User-Agent"] = userAgent ?: DEFAULT_USER_AGENT
            referer?.let { h["Referer"] = it }
            return h
        }

    /** True when the playlist supplies ClearKey keys (license_type=clearkey, or a key with no type). */
    val usesClearKey: Boolean
        get() = licenseKey != null && (licenseType == null || licenseType == "clearkey")

    override fun toString() = "$number   $name"

    companion object {
        const val DEFAULT_USER_AGENT = "Mozilla/5.0"
    }
}
