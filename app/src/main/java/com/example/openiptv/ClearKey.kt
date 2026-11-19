package com.example.openiptv

import android.util.Base64
import androidx.media3.common.C
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback

/** ClearKey support: turns a "kid_hex:key_hex[,kid_hex:key_hex...]" spec into an ExoPlayer DRM session manager. */
object ClearKey {
    private val HEX32 = Regex("^[0-9a-fA-F]{32}$")

    /** Returns null if [spec] is malformed. */
    fun json(spec: String): ByteArray? {
        val entries = spec.split(',').map { pair ->
            val parts = pair.trim().split(':')
            if (parts.size != 2 || !HEX32.matches(parts[0].trim()) || !HEX32.matches(parts[1].trim())) return null
            """{"kty":"oct","kid":"${b64(parts[0].trim())}","k":"${b64(parts[1].trim())}"}"""
        }
        return """{"keys":[${entries.joinToString(",")}],"type":"temporary"}""".toByteArray()
    }

    /** Returns null if [spec] is malformed. The keys are served locally; no license server is contacted. */
    fun sessionManager(spec: String): DrmSessionManager? {
        val body = json(spec) ?: return null
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .build(LocalMediaDrmCallback(body))
    }

    private fun b64(hex: String): String {
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
