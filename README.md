# Open IPTV (clean-room replacement)
Minimal Android / Android TV IPTV player. Loads **your own** M3U playlist URL and plays HLS/DASH/progressive streams with Media3.

Permissions: INTERNET, ACCESS_NETWORK_STATE only. No mic/voice, no ADB, no accessibility, no overlay,
no secure settings, no package install, no boot receiver, no self-updater, no hardcoded remote playlist.

Build: open in Android Studio, or `gradle :app:assembleDebug` with the Android SDK installed.
Controls (TV): Up/Down or CH+/CH- switch channel, OK opens channel list, Back closes it.
