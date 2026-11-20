package com.example.openiptv

import androidx.media3.common.C
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback

/** ClearKey support: turns a license_key value (see [ClearKeySpec]) into an ExoPlayer DRM session manager. */
object ClearKey {
    /** Returns null if [spec] is malformed. The keys are served locally; no license server is contacted. */
    fun sessionManager(spec: String): DrmSessionManager? {
        val keys = ClearKeySpec.parse(spec) ?: return null
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .build(LocalMediaDrmCallback(ClearKeySpec.toLicenseJson(keys)))
    }
}
